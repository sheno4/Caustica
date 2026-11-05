"""Adapt a privately extracted HLSL compute program to Caustica's Vulkan ABI.

Input and generated shaders belong in a private source pack outside the checkout.
The adapter preserves the original cloud scattering and uniform byte layouts.
Explicit host adaptations bridge cameras, tracing controls and missing-terrain
atmosphere boundaries. Original source and runtime resources stay private.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path


def type_size(kind: str) -> int:
    match = re.fullmatch(r"(float|uint|int|half|bool)([1-4])?(?:x([1-4]))?", kind)
    if not match:
        raise ValueError(f"Unsupported uniform field type: {kind}")
    return 4 * int(match[2] or 1) * int(match[3] or 1)


def struct_fields(source: str, name: str) -> tuple[list[dict], int]:
    body = re.search(r"struct\s+" + re.escape(name) + r"\s*\{(.*?)\};", source, re.S)
    if not body:
        raise ValueError(f"Missing uniform structure: {name}")
    fields, cursor = [], 0
    for match in re.finditer(r"\b(\w+)\s+(\w+)\s*(?:\[\s*(\d+)\s*\])?\s*;", body[1]):
        kind, member, count = match.groups()
        size = type_size(kind) * int(count or 1)
        fields.append(dict(name=member, type=kind, offset=cursor, size=size, elements=int(count or 0)))
        cursor += size
    return fields, (cursor + 15) & ~15


def host_camera_body(text: str) -> str:
    """Import host camera rays while retaining the source's metre/centimetre contract."""
    function = re.search(r"float3\s+GetNormalizeRayWorldDirection\s*\(\s*(?:in\s+)?float2\s+ScreenPosition\s*\)\s*\{", text)
    if not function:
        raise ValueError("The source program does not expose its camera-ray interface")
    cursor, depth = function.end(), 1
    while depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    replacement = """float3 GetNormalizeRayWorldDirection(float2 ScreenPosition)
{
    OriginalHostCamera camera = *((OriginalHostCamera*)originalPush.cameraAddress);
    float4 host = camera.column0 * ScreenPosition.x + camera.column1 * ScreenPosition.y
                + camera.column2 + camera.column3;
    float3 direction = host.xyz / host.w;
    return normalize(float3(direction.x, -direction.z, direction.y));
}"""
    text = text[:function.start()] + replacement + text[cursor:]
    origin = "(DFDemote(GetPrimaryView().WorldCameraOrigin)).xyz"
    ray = re.search(r"Ray\s+GetViewRay(?:ForCoarseTrace)?\s*\(.*?\n\}", text, re.S)
    if not ray or ray[0].count(origin) != 1:
        raise ValueError("The source camera origin interface changed")
    text = text[:ray.start()] + ray[0].replace(origin,
        "((OriginalHostCamera*)originalPush.cameraAddress)->sourceOriginMeters * 100.0f") + text[ray.end():]
    composite = re.search(r"Ray\s+GetViewRay_FIXED_For_CompositeWithScreenCS\s*\(\s*in\s+float2\s+uv\s*\)\s*\{.*?\n\}", text, re.S)
    if composite:
        text = text[:composite.start()] + """Ray GetViewRay_FIXED_For_CompositeWithScreenCS(in float2 uv)
{
    return GetViewRay(uv);
}""" + text[composite.end():]
        distance = re.search(r"float\s+GetDistanceFromSceneDepth\s*\(\s*in\s+float2\s+uv,\s*in\s+float\s+SceneDepth\s*\)\s*\{.*?\n\}", text, re.S)
        if not distance:
            raise ValueError("The source depth-to-distance camera interface changed")
        text = text[:distance.start()] + """float GetDistanceFromSceneDepth(in float2 uv, in float SceneDepth)
{
    float3 direction = GetNormalizeRayWorldDirection(uv * 2.0f - 1.0f);
    float3 forward = GetNormalizeRayWorldDirection(float2(0, 0));
    return SceneDepth * 0.01f / dot(direction, forward);
}""" + text[distance.end():]
    if "void GetCompositedSceneColor(" in text:
        text = host_atmosphere_composition(text)
        for radicand in (
            "ViewHeight * ViewHeight - BottomRadius * BottomRadius",
            "h * h - GetRBot() * GetRBot()",
            "dot(view_ray.o - GetEarthCenter(), view_ray.o - GetEarthCenter()) - GetRBot() * GetRBot()",
        ):
            text = text.replace("sqrt(" + radicand + ")", "sqrt(max(0.0f, " + radicand + "))")
        # The sky-side tangent maps to the common horizon row, including float rounding at the boundary.
        text = text.replace("Coord = sqrt(Coord);", "Coord = sqrt(max(0.0f, Coord));")
    return text


def host_atmosphere_composition(text: str) -> str:
    """Fade real surfaces into the same directional background used without terrain."""
    # Surface AP carries scene-specific artistic scaling. It cannot also be the
    # background fade target: that target must not depend on a terrain depth hit.
    blocks = (
        (r"if\(\(EnableHighQualityGodRays == 0 \|\| EnableBetterHorizonTransition == 0\)"
         r" && \(bFadeHorizonByAP \|\| terrain_blend_factor != 1\)\)\s*\{",
         "AtmColor = lerp(AtmColor, AP.xyz * UnrealPreExposure, falloff);"),
        (r"if\(terrain_blend_factor < 1\)\s*\{",
         "dist_adjusted = max(dist_adjusted, lerp(dist_adjusted, CloudConstantsUniform.Data_CloudMaxTraceDistance, pow2(1 - terrain_blend_factor)));"),
    )
    for pattern, marker in blocks:
        matches = list(re.finditer(pattern, text))
        if len(matches) != 1:
            raise ValueError("The native atmosphere terrain composition interface changed")
        match = matches[0]
        end = braced_end(text, match.end() - 1)
        if text[match.end():end].count(marker) != 1:
            raise ValueError("The native atmosphere terrain composition body changed")
        text = text[:match.start()] + text[end:]
    return text


def host_terrain_fade_boundary(text: str) -> str:
    """Match the host's cylindrical terrain boundary without changing physical ray depth."""
    marker = "terrain_blend_factor = 1 - smoothstep(fade_distance * FadeStartFactor, fade_distance, dist);"
    if text.count(marker) != 1:
        raise ValueError("The native terrain-fade distance interface changed")
    distance = re.compile(r"float fade_distance = clamp\([^;]+;")
    text, count = distance.subn("float fade_distance = MaxVisibleDistance;", text)
    if count != 1:
        raise ValueError("The native terrain-fade range interface changed")
    text = text.replace(marker,
        "terrain_blend_factor = 1 - smoothstep(fade_distance * FadeStartFactor, fade_distance, "
        "dist * max(length(view_ray.d.xy), abs(view_ray.d.z)));", 1)
    condition = re.compile(r"if\((depth|InputParameters\.DeviceZ) && EnableTerrainFade\)")
    text, count = condition.subn(r"if(\1 && EnableTerrainFade && MaxVisibleDistance > 0.0f)", text)
    if count != 1:
        raise ValueError("The native terrain-fade enable interface changed")
    return text


def host_environment_depth(text: str) -> str:
    """Classify environment and terrain using the same host boundary as composition."""
    function = re.search(r"float\s+GetDistanceFromSceneDepth\s*\(\s*float2\s+uv\s*,\s*float\s+DeviceZ\s*,\s*float\s+h_real\s*,\s*float\s+tan_dist\s*\)\s*\{", text)
    if function:
        end = braced_end(text, function.end() - 1)
        body = text[function.end():end]
        marker = "terrain_blend_factor = 1 - smoothstep(fade_distance * FadeStartFactor, fade_distance, dist);"
        if body.count(marker) != 1 or body.count("if(EnableTerrainFade)") != 1:
            raise ValueError("The native depth-classification terrain boundary changed")
        distance = re.compile(r"float fade_distance = clamp\([^;]+;")
        body, count = distance.subn("const float3 hostTerrainRay = normalize(ScreenVector);\n            float fade_distance = MaxVisibleDistance;", body)
        if count != 1:
            raise ValueError("The native depth-classification fade range changed")
        body = body.replace(marker,
            "terrain_blend_factor = 1 - smoothstep(fade_distance * FadeStartFactor, fade_distance, "
            "dist * max(length(hostTerrainRay.xy), abs(hostTerrainRay.z)));", 1)
        body = body.replace("if(EnableTerrainFade)", "if(EnableTerrainFade && MaxVisibleDistance > 0.0f)", 1)
        text = text[:function.end()] + "\n    if (DeviceZ <= 0.0f) return MaxDistance;" + body + text[end:]
        # Height/tangent preparation remains ABI-compatible and is unused by
        # the fixed host fade range. A missing host window disables its branch too.
        if text.count("if(EnableTerrainFade)") != 1:
            raise ValueError("The native depth-classification range preparation changed")
        text = text.replace("if(EnableTerrainFade)", "if(EnableTerrainFade && MaxVisibleDistance > 0.0f)", 1)
    return text


def host_stars_background(text: str) -> str:
    """The host has the native cubemap, without the source's separate star catalogue pass."""
    if not re.search(r"\bvoid\s+CompositeAtmosphereCS\s*\(", text):
        return text
    sample = re.compile(
        r"const\s+float2\s+uv_viewport\s*=\s*[^;\n]+;\s*"
        r"const\s+float4\s+stars_tex_val\s*=\s*StarsTexture\.SampleLevel\(StarsTextureSampler,\s*uv_viewport,\s*0\);\s*"
        r"Star\s*=\s*float4\(UnNormalizeStarsValue\(StarsNoColorMode\s*\?\s*stars_tex_val\.xxx\s*:\s*stars_tex_val\.xyz\),\s*1\.0f\s*\)"
        r"\s*\*\s*StarsIntensityMask\(view_ray\.d,\s*MoonDirection\)\s*"
        r"\+\s*StarsBackgroundIntensity\s*\*\s*StarsBackground\.SampleLevel\(StarsTextureSampler,\s*view_ray\.d,\s*0\);"
    )
    replacement = """// Native source Z is the sky's vertical axis; catalogue stars have no host producer.
                float hostStarsSin, hostStarsCos;
                sincos(HostStarsRotationRadians, hostStarsSin, hostStarsCos);
                const float3 hostStarsDirection = float3(
                    hostStarsCos * view_ray.d.x - hostStarsSin * view_ray.d.y,
                    hostStarsSin * view_ray.d.x + hostStarsCos * view_ray.d.y,
                    view_ray.d.z);
                Star = StarsBackgroundIntensity * StarsBackground.SampleLevel(StarsTextureSampler, hostStarsDirection, 0);"""
    text, count = sample.subn(replacement, text)
    if count != 1:
        raise ValueError("The native star-background composition interface changed")
    return text


def host_view_trace_ranges(text: str) -> str:
    """View limits leave atmosphere lookup coordinates and light/shadow ranges unchanged."""
    if re.search(r"\bvoid\s+SkyTraceCS\s*\(", text):
        replacements = (
            ("float tracable_cloud_range_max = CloudConstantsUniform.Data_CloudMaxTraceDistance;",
             "float tracable_cloud_range_max = min(CloudConstantsUniform.Data_CloudMaxTraceDistance, HostCloudTraceDistanceMeters);"),
            ("float tracable_cirrus_range_max = CloudConstantsUniform.Data_CloudMaxTraceDistanceCirrus;",
             "float tracable_cirrus_range_max = min(CloudConstantsUniform.Data_CloudMaxTraceDistanceCirrus, HostCirrusTraceDistanceMeters);"),
            ("const float CirrusDensity = GetCloudDensityCirrus( pp, cst, view_dist, log2_view_dist_x_sin_alpha, sdf_eval, mips_flags, skip_keep_density );",
             """const float hostCirrusCap = min(CloudConstantsUniform.Data_CloudMaxTraceDistanceCirrus, HostCirrusTraceDistanceMeters);
        const float hostCirrusFade = 1.0f - smoothstep(0.95f * hostCirrusCap, hostCirrusCap, view_dist);
        const float CirrusDensity = GetCloudDensityCirrus( pp, cst, view_dist, log2_view_dist_x_sin_alpha, sdf_eval, mips_flags, skip_keep_density ) * pow2(hostCirrusFade);"""),
        )
        for original, replacement in replacements:
            if text.count(original) != 1:
                raise ValueError("The native cloud view tracing interface changed")
            text = text.replace(original, replacement, 1)
        fade = re.compile(r"float\s+GetCloudDistanceFade\s*\(\s*const\s+float\s+t\s*\)\s*\{.*?\n\}", re.S)
        text, count = fade.subn("""float GetCloudDistanceFade(const float t)
{
    const float hostCloudCap = min(CloudConstantsUniform.Data_CloudMaxTraceDistance, HostCloudTraceDistanceMeters);
    return 1.0f - smoothstep(0.95f * hostCloudCap, hostCloudCap, t);
}""", text)
        if count != 1:
            raise ValueError("The native cloud view distance fade interface changed")
    else:
        original = "cloud_cone_trace_range.y = max(0,cloud_cone_trace_range.y);"
        if text.count(original) != 1:
            raise ValueError("The native coarse cloud tracing interface changed")
        # Coarse tracing covers base and alto layers; the independent cirrus range is irrelevant.
        text = text.replace(original,
                            "cloud_cone_trace_range.y = max(0, min(cloud_cone_trace_range.y, min(CloudConstantsUniform.Data_CloudMaxTraceDistance, HostCloudTraceDistanceMeters)));", 1)
    return text


def host_native_feature_selection(text: str) -> str:
    """Select the original alto displacement branch using its native modelling flag."""
    if not re.search(r"\bvoid\s+SkyTraceCS\s*\(", text):
        return text
    original = "const bool EnableAltoDisplacement1Texture = (0 == 1);"
    replacement = "const bool EnableAltoDisplacement1Texture = IsBitSet( SkyConstantsUniform.Data_CloudModellingFlags, 7 );"
    if text.count(original) != 1:
        raise ValueError("The native alto displacement selection interface changed")
    return text.replace(original, replacement, 1)


def host_air_background_helpers() -> str:
    """One host boundary policy shared by native background producers."""
    return """
float HostAirBackgroundDistance(float h, float rBot, float tTop, float tBot,
                                float trace_distance_max, out float groundWeight)
{
    groundWeight = 0.0f;
    const float farDistance = min(tTop, trace_distance_max);
    if (tBot < 0.0f) return farDistance;
    const float tangentDistance = sqrt(max(0.0f, h * h - rBot * rBot));
    const float horizonBias = lerp(0.1f, 0.7f, smoothstep(0.0f, 5000.0f, h - rBot));
    const float horizonFactor = sqrt(smoothstep(0.0f, max(1.0e-6f, tangentDistance * horizonBias), tBot));
    groundWeight = 1.0f - horizonFactor;
    const float groundPadding = lerp(5000.0f, 50000.0f, saturate(tBot / max(1.0e-6f, tangentDistance * 0.2f)));
    const float finiteDistance = min(farDistance, tBot + groundPadding);
    return lerp(farDistance, finiteDistance, groundWeight);
}
float3 HostAirBackgroundPosition(float3 position, float3 earth_center, float rBot, bool continueAir)
{
    if (continueAir)
    {
        const float3 radial = position - earth_center;
        const float radius = length(radial);
        if (radius < rBot + 0.3f)
            return earth_center + radial * ((rBot + 0.3f) / max(radius, 1.0e-6f));
    }
    return position;
}
"""


def braced_end(text: str, opening: int) -> int:
    """Return the exclusive end of an already located native block."""
    cursor, depth = opening + 1, 1
    while depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    return cursor


def host_cloud_scene_occlusion(text: str) -> str:
    """Only captured host scene depth may occlude clouds; the atmosphere sphere is not terrain."""
    function = re.search(r"TraceSkyRanges\s+TraceRangeSetup\s*\([^{}]*\)\s*\{", text)
    if not function:
        raise ValueError("The native cloud view-range interface changed")
    end = braced_end(text, function.end() - 1)
    body = text[function.end():end]
    patterns = (
        r"\bconst\s+float\s+r2_earth\s*=\s*pow2\(\s*GetEarthRadius\(\s*\)\s*\)\s*;",
        r"\bfloat2\s+earth_t\s*=\s*float2\(\s*1e\+12\s*,\s*1e\+12\s*\)\s*;",
        r"\bif\s*\(\s*r2_earth\s*>=\s*h_sqr\s*\)\s*\{\s*earth_t\s*=\s*raw_sphere_trace\(\s*tt\s*,\s*r2_earth\s*\)\s*;\s*\}",
        r"\bif\s*\(\s*SphereFrontVisible\(\s*earth_t\s*\)\s*\)\s*\{\s*ret1\.first_occluder_depth\s*=\s*earth_t\.x\s*;\s*\}",
    )
    for pattern in patterns:
        body, count = re.subn(pattern, "", body)
        if count != 1:
            raise ValueError("The native virtual-ground cloud occluder interface changed")
    return text[:function.end()] + body + text[end:]


def host_cloud_height(text: str) -> str:
    """Translate cloud coordinates while keeping the camera and atmosphere in host space."""
    local_functions = (
        "GetCloudSDF", "GetCloudDensity", "GetCloudSDF_Cirrus",
        "CalculateHotsureEffectAdditiveDensity", "GetCloudLayerMinClampHeight",
        "GetNormalizedRainDepth", "GetCloudsAO", "CloudsAOTraceCS",
    )
    saved = []
    for name in local_functions:
        function = re.search(r"\b(?:void|float[1-4]?|SDFEval|DensityEval)\s+" + name
                             + r"\s*\([^{}]*\)\s*\{", text)
        if not function:
            continue
        opening = function.end() - 1
        end = braced_end(text, opening)
        signature, body = text[function.start():opening], text[opening:end]
        if name in ("GetCloudSDF", "GetCloudDensity"):
            signature, positions = re.subn(r"\bflat_world_position\b", "hostCloudWorldPosition", signature)
            signature, heights = re.subn(r"\bh\b", "hostCloudWorldAltitude", signature)
            if positions != 1 or heights != 1:
                raise ValueError("The native cloud density coordinate interface changed: " + name)
            body = """{
    const float3 flat_world_position = hostCloudWorldPosition - float3(0, 0, HostCloudHeightOffsetMeters);
    const float h = hostCloudWorldAltitude - HostCloudHeightOffsetMeters;
""" + body[1:]
        elif name == "GetCloudsAO":
            body = "{\n    WorldPos.z -= HostCloudHeightOffsetMeters;\n" + body[1:]
        elif name == "CloudsAOTraceCS":
            body, count = re.subn(r"cst\.view_ray\.o\s*=\s*float3\(world_pos\.xy,\s*0\s*\)",
                                 "cst.view_ray.o = float3(world_pos.xy, HostCloudHeightOffsetMeters)", body)
            if count != 1:
                raise ValueError("The native cloud AO ray origin changed")
        # These helpers are called by both cloud-local density and world-space tracing.
        for helper in ("GetCloudLayerMinClampHeight", "GetNormalizedRainDepth"):
            signature = re.sub(r"\b" + helper + r"\b", helper + "Local", signature)
            body = re.sub(r"\b" + helper + r"\b", helper + "Local", body)
        block = signature + body
        if name == "GetCloudLayerMinClampHeight":
            block += """
float2 GetCloudLayerMinClampHeight()
{
    return GetCloudLayerMinClampHeightLocal() + HostCloudHeightOffsetMeters;
}
"""
        elif name == "GetNormalizedRainDepth":
            block += """
float GetNormalizedRainDepth(const float hostCloudWorldAltitude)
{
    return GetNormalizedRainDepthLocal(hostCloudWorldAltitude - HostCloudHeightOffsetMeters);
}
"""
        token = "__HOST_CLOUD_NATIVE_FUNCTION_" + str(len(saved)) + "__"
        saved.append((token, block))
        text = text[:function.start()] + token + text[end:]
    # Unmasked references belong to world-space shell bounds, stepping, light/shadow
    # ranges, or shading. Native density functions retain their authored heights.
    heights = re.compile(r"\bCloudConstantsUniform\.Data_(?:CloudMinHeightWithCurvature|"
                         r"CloudMaxHeightForBaseAltoTrace|CloudMinHeight|CloudMaxHeight|CloudLayersStartHeight)\b")
    text = heights.sub(lambda match: "(" + match[0] + " + HostCloudHeightOffsetMeters)", text)
    # AO integrates along a translated ray while retaining native positive ray
    # distances and volume spacing, including when clouds move below altitude zero.
    for token, block in saved:
        text = text.replace(token, block)
    return text


def host_godray_background(text: str) -> str:
    """Clone the native unscaled quadrature privately; leave the surface AP loop intact."""
    entry = re.search(r"\[numthreads\s*\([^)]*\)\]\s*void\s+AerialPerspectiveLutCS\s*\(", text)
    if not entry:
        return text
    loop = re.search(r"\[loop\]\s*for\(uint i = 0; i < godray_loop_num \+ sub_loop_num; i\+\+\)", text[entry.end():])
    if not loop:
        raise ValueError("The native godray quadrature loop changed")
    loop_start = entry.end() + loop.start()
    opening = text.index("{", entry.end() + loop.end())
    closing = braced_end(text, opening)
    body = text[opening:closing]
    # Everything before AP's artistic scaling is the original unscaled sky integral.
    # Extract it from the private input instead of copying recovered source into Git.
    scale = "MultiScattering = MultipleScatteringScaleAP"
    if body.count(scale) != 1:
        raise ValueError("The native godray/surface scattering split changed")
    background = text[loop_start:opening] + body[:body.index(scale)] + "\n            t_prev = t;\n        }\n    }\n"
    background = re.sub(r"\btracable_length\b", "hostBackgroundDistance", background)
    background = re.sub(r"\bt_prev\b", "hostBackgroundPrevious", background)
    background = re.sub(r"\bsub_loop_delta_t\b", "hostBackgroundDelta", background)
    bias = re.compile(r"if\(tTop > 0\.0f && tBot >= 0\.0f && \(h > GetRBot\(\)\)\)\s*tracable_length_with_bias = [^;]+;")
    background, count = bias.subn("", background)
    if count != 1:
        raise ValueError("The native godray ground-range clamp changed")
    sample = "float3 p_i = start_pos + t * view_ray.d;"
    if background.count(sample) != 1:
        raise ValueError("The native godray sample position changed")
    background = background.replace(sample, sample + "\n        p_i = HostAirBackgroundPosition(p_i, earth_center, GetRBot(), true);", 1)
    scattering = "float factor_ms = ComputeHorizonMultipleScatteringFactor(length_pc, GetRBot(), GetRTop(), tTop,tBot);"
    if background.count(scattering) != 1:
        raise ValueError("The native godray multiple-scattering interface changed")
    background = background.replace(scattering, scattering + """
            factor_ms = lerp(ComputeHorizonMultipleScatteringFactor(length_pc, GetRBot(), GetRTop(), tTop, -1.0f), factor_ms, hostGroundWeight);
""", 1)
    extension = """
    // Surface AP above retains its native positions, range, scale and volume writes.
    // Only the ground-side sky background repeats the native unscaled quadrature.
    if (EnableHighQualityGodRays > 0 && tBot >= 0.0f)
    {
        float hostGroundWeight;
        const float hostBackgroundDistance = HostAirBackgroundDistance(h, GetRBot(), tTop, tBot, 1e+12, hostGroundWeight);
        const float hostBackgroundDelta = max(4000.0f, (hostBackgroundDistance - base_length) / sub_loop_num);
        float hostBackgroundPrevious = 0.0f;
        total_inscattering_without_scale = 0.0f;
        tr_total_without_scale = 1.0f;
""" + background + "\n    }\n"
    output = re.search(r"\n\s*if\(EnableHighQualityGodRays > 0\)", text[closing:])
    if not output:
        raise ValueError("The native godray background output changed")
    output_start = closing + output.start()
    text = text[:output_start] + extension + text[output_start:]
    return text[:entry.start()] + host_air_background_helpers() + text[entry.start():]


def host_background_lut(text: str) -> str:
    """Missing terrain receives direction-dependent native air transport, not virtual planet radiance."""
    if re.search(r"\bvoid\s+AerialPerspectiveLutCS\s*\(", text):
        return host_godray_background(text)
    if re.search(r"\bvoid\s+ATMViewLutCS\s*\(", text):
        text = re.sub(r"\s*if\s*\(SumGodrayFlag\s*>\s*0\s*&&\s*EnableHighQualityGodRays\s*>\s*0\)\s*\{\s*RWATMViewLut\[id\.xy\]\s*=\s*0\.0;\s*return;\s*\}", "", text, count=1)
        function = re.search(r"ResultSingleScattering\s+IntegrateSingleScattering\s*\(", text)
        if not function:
            raise ValueError("The native atmosphere integration interface changed")
        start = text.index("{", function.end())
        cursor = braced_end(text, start)
        body = text[start:cursor]
        distance = "trace_distance = min(trace_distance, trace_distance_max);"
        if body.count(distance) != 1:
            raise ValueError("The native atmosphere integration range changed")
        # AP's horizon bias and ground-range padding define a host boundary policy for absent
        # terrain. The ray, atmosphere coefficients and source quadrature remain native.
        body = body.replace(distance, distance + """
    const bool hostAirContinuation = tBot >= 0.0f;
    float hostGroundWeight;
    trace_distance = HostAirBackgroundDistance(h, rBot, tTop, tBot, trace_distance_max, hostGroundWeight);
""", 1)
        sample = "float3 p_i = view_ray.o + t * view_ray.d;"
        if body.count(sample) != 1:
            raise ValueError("The native atmosphere sampling position changed")
        body = body.replace(sample, sample + "\n        p_i = HostAirBackgroundPosition(p_i, earth_center, rBot, hostAirContinuation);\n", 1)
        body = body.replace("if(bUseCloudShadow && t < shadow_fade_dist )",
                            "if((bUseCloudShadow || hostGroundWeight > 0.0f) && t < shadow_fade_dist )", 1)
        shadow = "tr_cloud_sun_ray = exp(-pow(step_cloud_shadow_op, max(1, ShadowTraceConstants_HackGodRayStrength)));"
        if body.count(shadow) != 1:
            raise ValueError("The native atmosphere cloud-shadow integration changed")
        body = body.replace(shadow,
                            "step_cloud_shadow_op *= bUseCloudShadow ? 1.0f : hostGroundWeight;\n\t\t\t" + shadow, 1)
        scattering = "float factor_ms = ComputeHorizonMultipleScatteringFactor(length_pc, rBot, rTop, tTop, tBot);"
        if body.count(scattering) != 1:
            raise ValueError("The native atmosphere horizon multiple-scattering interface changed")
        body = body.replace(scattering, scattering + """
        if (hostAirContinuation)
            factor_ms = lerp(ComputeHorizonMultipleScatteringFactor(length_pc, rBot, rTop, tTop, -1.0f), factor_ms, hostGroundWeight);
""", 1)
        text = text[:start] + body + text[cursor:]
        text = text[:function.start()] + host_air_background_helpers() + text[function.start():]
        text = text.replace("sqrt(ViewHeight * ViewHeight - GetRBot() * GetRBot())",
                            "sqrt(max(0.0f, ViewHeight * ViewHeight - GetRBot() * GetRBot()))")
    return text


def adapt(source_path: Path, output_path: Path, host_camera: bool = False,
          host_terrain_boundary: bool = False, cloud_height: bool = False,
          cloud_foreground: bool = False) -> dict:
    source = source_path.read_text(encoding="utf-8")
    root = re.search(r"cbuffer\s+_RootShaderParameters\s*\{(.*?)\}", source, re.S)
    if not root:
        raise ValueError("An original root parameter block is required")
    common = []
    for match in re.finditer(r"(\w+)\s+(\w+)\s*(?:\[(\d+)\])?\s*:\s*packoffset\(c(\d+)(?:\.([xyzw]))?\)\s*;", root[1]):
        kind, name, count, register, component = match.groups()
        elements = int(count or 0)
        if elements and type_size(kind) % 16:
            raise ValueError(f"Array element requires an explicit 16-byte stride adapter: {name}")
        common.append(dict(name=name, type=kind,
                           offset=int(register) * 16 + ("xyzw".index(component) * 4 if component else 0),
                           size=type_size(kind) * (elements or 1), elements=elements))
    declarations = len(re.findall(r"packoffset\s*\(", root[1]))
    if declarations != len(common):
        raise ValueError("Root parameter declarations were not fully decoded")
    common.sort(key=lambda field: field["offset"])
    common_size = (max((field["offset"] + field["size"] for field in common), default=16) + 15) & ~15
    view_trace_ranges = bool(host_camera and re.search(r"\bvoid\s+(?:SkyTraceCS|CoarseTraceCS)\s*\(", source))
    cloud_view_trace = bool(host_camera and re.search(r"\bvoid\s+SkyTraceCS\s*\(", source))
    if view_trace_ranges:
        required_bytes = 8
        if any(field["offset"] < required_bytes for field in common):
            raise ValueError(f"The host tracing interface requires {required_bytes} unused root bytes")
        common.extend([dict(name="HostCloudTraceDistanceMeters", type="float", offset=0, size=4, elements=0),
                       dict(name="HostCirrusTraceDistanceMeters", type="float", offset=4, size=4, elements=0)])
        common.sort(key=lambda field: field["offset"])
    stars_rotation = bool(host_camera and re.search(r"\bvoid\s+CompositeAtmosphereCS\s*\(", source))
    if stars_rotation:
        if any(field["offset"] < 4 for field in common):
            raise ValueError("The host star rotation interface requires four unused root bytes")
        common.append(dict(name="HostStarsRotationRadians", type="float", offset=0, size=4, elements=0))
        common.sort(key=lambda field: field["offset"])
    if cloud_height:
        offset = next((offset for offset in range(0, common_size, 4)
                       if all(offset + 4 <= field["offset"] or offset >= field["offset"] + field["size"]
                              for field in common)), None)
        if offset is None:
            raise ValueError("The host cloud-height interface requires four unused root bytes")
        common.append(dict(name="HostCloudHeightOffsetMeters", type="float", offset=offset, size=4, elements=0))
        common.sort(key=lambda field: field["offset"])
    lines = ["struct OriginalRootParameters {"]
    cursor = 0
    for field in common:
        if cursor > field["offset"]:
            raise ValueError(f"Overlapping root parameter: {field['name']}")
        while cursor < field["offset"]:
            lines.append(f"    uint _padding{cursor};")
            cursor += 4
        suffix = f"[{field['elements']}]" if field['elements'] else ""
        lines.append(f"    {field['type']} {field['name']}{suffix};")
        cursor += field["size"]
    while cursor < common_size:
        lines.append(f"    uint _padding{cursor};")
        cursor += 4
    lines.extend(["};"])
    if host_camera:
        lines.extend(["struct OriginalHostCamera {", "    float4 column0;", "    float4 column1;",
                      "    float4 column2;", "    float4 column3;", "    float3 sourceOriginMeters;",
                      "    float metersPerSceneUnit;", "};"])
    lines.extend(["struct OriginalProgramPush {", "    uint64_t parametersAddress;", "    uint64_t bindingsAddress;"])
    if host_camera:
        lines.append("    uint64_t cameraAddress;")
    lines.extend(["};", "[[vk::push_constant]] OriginalProgramPush originalPush;"])
    for field in common:
        value = f"(((OriginalRootParameters*)originalPush.parametersAddress)->{field['name']})"
        lines.append(f"#define {field['name']} {value}" if field['elements'] else
                     f"static {field['type']} {field['name']} = {value};")
    text = source[:root.start()] + "\n".join(lines) + "\n" + source[root.end():]

    uniforms, cursor = [], common_size
    for match in list(re.finditer(r"ConstantBuffer\s*<\s*(\w+)\s*>\s+(\w+)\s*;", text)):
        kind, name = match.groups()
        fields, size = struct_fields(source, kind)
        uniforms.append(dict(name=name, type=kind, offset=cursor, size=size, fields=fields))
        text = text.replace(match[0], f"#define {name} (*({kind}*)(originalPush.parametersAddress + uint64_t({cursor})))")
        cursor += size

    bindings = []
    resource_pattern = (r"(?m)^\s*((?:RW)?(?:Texture(?:1D|2D|3D|Cube)(?:Array)?|"
                        r"StructuredBuffer|ByteAddressBuffer|Buffer)(?:\s*<[^;\n]+>)?|SamplerState)\s+(\w+)\s*;")
    resource_source = text
    for match in list(re.finditer(resource_pattern, resource_source)):
        prefix = resource_source[:match.start()]
        if prefix.count("{") != prefix.count("}"):
            continue
        kind, name = match.groups()
        kind = re.sub(r"\s+", "", kind)
        if kind.startswith(("Texture", "RWTexture")) and "<" not in kind:
            kind += "<float4>"
        original_kind = kind
        if kind.startswith("RWTexture") and re.search(r"<(uint|int)>", kind):
            kind = kind[:-1] + (",0,37>" if "<uint>" in kind else ",0,27>")
        elif kind in ("RWBuffer<uint>", "RWBuffer<int>"):
            kind = kind[:-1] + (",37>" if "<uint>" in kind else ",27>")
        slot = len(bindings)
        heap = "Sampler" if kind == "SamplerState" else "Resource"
        replacement = (f"\n{kind} getOriginal_{name}() {{ return {heap}DescriptorHeap[((uint*)originalPush.bindingsAddress)[{slot}]]; }}\n"
                       f"#define {name} getOriginal_{name}()\n")
        text = text.replace(match[0], replacement)
        bindings.append(dict(name=name, type=kind, originalType=original_kind, slot=slot, byteOffset=slot * 4, heap=heap))
    # Helper parameters must use the same typed integer texel format as heap getters.
    text = re.sub(r"\bRWBuffer\s*<\s*uint\s*>", "RWBuffer<uint,37>", text)
    text = re.sub(r"\bRWBuffer\s*<\s*int\s*>", "RWBuffer<int,27>", text)
    text = re.sub(r"^#(?:line|pragma).*?$", "", text, flags=re.M)
    # Slang resolves structure declarations irrespective of source order.
    text = re.sub(r"(?m)^\s*struct\s+\w+\s*;\s*$", "", text)
    # DXC accepts these implicit conversions and constant folds integer max in array extents.
    text = re.sub(r"max\(\s*(\d+)\s*,\s*(\d+)\s*\)", lambda m: str(max(int(m[1]), int(m[2]))), text)
    text = text.replace("lerp(similarity > 0.15, simWeight,", "lerp(float4(similarity > 0.15), simWeight,")
    text = re.sub(r"uint16_t\s+width,\s*height;(?=\s*tex.GetDimensions)", "uint width, height;", text)
    if host_camera:
        text = host_camera_body(text)
        text = host_environment_depth(text)
        text = host_stars_background(text)
        text = host_background_lut(text)
        text = host_native_feature_selection(text)
        if view_trace_ranges:
            text = host_view_trace_ranges(text)
        if cloud_view_trace:
            text = host_cloud_scene_occlusion(text)
    terrain_boundary = host_terrain_boundary or bool(host_camera and "void GetCompositedSceneColor(" in source)
    depth_terrain_boundary = bool(host_camera and re.search(r"float\s+GetDistanceFromSceneDepth\s*\(\s*float2\s+uv\s*,\s*float\s+DeviceZ\s*,", source))
    if terrain_boundary:
        text = host_terrain_fade_boundary(text)
    if cloud_height:
        text = host_cloud_height(text)
    if cloud_foreground:
        for module_name in ("host_cloud_prefix_depth", "host_cloud_prefix",
                            "host_cloud_prefix_transport", "host_cloud_prefix_composite"):
            spec = importlib.util.spec_from_file_location(module_name, Path(__file__).with_name(module_name + ".py"))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            transform = getattr(module, "host_cloud_prefix_trace" if module_name == "host_cloud_prefix" else module_name)
            text = transform(text)
            if module_name == "host_cloud_prefix" and cloud_view_trace:
                text = module.HOST_CLOUD_PREFIX_DECLARATIONS + "\n" + text
        resource_source = text
        for match in list(re.finditer(resource_pattern, resource_source)):
            prefix = resource_source[:match.start()]
            if prefix.count("{") != prefix.count("}"):
                continue
            kind, name = match.groups()
            kind = re.sub(r"\s+", "", kind)
            slot = len(bindings)
            heap = "Sampler" if kind == "SamplerState" else "Resource"
            replacement = (f"\n{kind} getOriginal_{name}() {{ return {heap}DescriptorHeap[((uint*)originalPush.bindingsAddress)[{slot}]]; }}\n"
                           f"#define {name} getOriginal_{name}()\n")
            # Accessors must follow the push declaration and precede every helper use.
            text = text.replace(match[0], "", 1)
            push_marker = "[[vk::push_constant]] OriginalProgramPush originalPush;"
            text = text.replace(push_marker, push_marker + replacement, 1)
            bindings.append(dict(name=name, type=kind, originalType=kind, slot=slot, byteOffset=slot * 4, heap=heap))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(text, encoding="utf-8")
    return dict(schemaVersion=1, sourceSha256=hashlib.sha256(source_path.read_bytes()).hexdigest(),
                sourceBytes=source_path.stat().st_size, commonByteSize=common_size, commonFields=common,
                parametersByteSize=cursor, uniformBlocks=uniforms, bindings=bindings,
                pushByteSize=24 if host_camera else 16,
                pushFields=[dict(name="parametersAddress", offset=0, size=8),
                            dict(name="bindingsAddress", offset=8, size=8)] +
                           ([dict(name="cameraAddress", offset=16, size=8)] if host_camera else []),
                mathematicalFunctionsChanged=host_camera or terrain_boundary or cloud_height or cloud_foreground, viewTraceRangesCapped=view_trace_ranges,
                hostCloudForeground=dict(enabled=cloud_foreground, physicalDistanceUnit="metres",
                    separateSurfacePrefix=True if cloud_foreground else None,
                    duplicateRaymarch=False if cloud_foreground else None),
                hostCloudHeight=dict(enabled=cloud_height, field="HostCloudHeightOffsetMeters" if cloud_height else None,
                    nativeDensityCoordinatesPreserved=True, atmosphereCoordinatesPreserved=True,
                    additionalUniformBytes=0),
                hostCloudOcclusion=dict(enabled=cloud_view_trace,
                    occluder="captured host scene depth" if cloud_view_trace else None,
                    virtualGroundClipsClouds=False if cloud_view_trace else None),
                hostTerrainBoundary=dict(enabled=terrain_boundary or depth_terrain_boundary,
                    distanceMetric="dist * max(length(nativeRay.xy), abs(nativeRay.z))" if terrain_boundary or depth_terrain_boundary else None,
                    physicalRayDistanceChanged=False, additionalUniformBytes=0),
                hostAtmosphereComposition=dict(enabled=stars_rotation,
                    sharedTerrainSkyBackground=stars_rotation,
                    surfaceApUsesPhysicalDistance=stars_rotation,
                    backgroundOverwrittenBySurfaceAp=False if stars_rotation else None),
                hostCloudTracing=dict(enabled=view_trace_ranges,
                    distanceFadeBeginFraction=0.95 if cloud_view_trace else None,
                    originalStepBudgetsPreserved=True,
                    originalAtmosphereDistanceUniformsPreserved=True),
                hostNativeFeatureSelection=dict(enabled=cloud_view_trace,
                    altoDisplacement1FlagBit=7 if cloud_view_trace else None,
                    selectionSource="Original CloudsAOTraceCS GetCloudSDF" if cloud_view_trace else None,
                    originalBranchAlgorithmsPreserved=True),
                hostStarsBackground=dict(enabled=stars_rotation,
                    rotationField="HostStarsRotationRadians" if stars_rotation else None,
                    rotationAxis="source Z" if stars_rotation else None,
                    catalogueTextureSampled=False if stars_rotation else None,
                    originalDescriptorSlotsPreserved=True),
                cameraBridge=dict(enabled=host_camera, byteSize=80 if host_camera else 0,
                                  sourceAxes="host X, negative host Z, host Y",
                                  replacedInterfaces=(["GetNormalizeRayWorldDirection", "source ray origin"] +
                                    (["GetViewRay_FIXED_For_CompositeWithScreenCS", "GetDistanceFromSceneDepth"]
                                     if "GetViewRay_FIXED_For_CompositeWithScreenCS" in source else [])) if host_camera else []))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--host-camera", action="store_true",
                        help="Import host inverse-projection rays instead of the original Unreal view basis")
    parser.add_argument("--host-terrain-boundary", action="store_true",
                        help="Use the host cylindrical terrain fade, retaining the source camera and push ABI")
    parser.add_argument("--host-cloud-height", action="store_true",
                        help="Translate cloud density, bounds, shadows and AO without moving the atmosphere")
    parser.add_argument("--host-cloud-foreground", action="store_true",
                        help="Transport the physical surface prefix independently of the full cloud integral")
    args = parser.parse_args()
    checkout = Path(__file__).resolve().parents[2]
    for path in (args.source, args.output):
        if path.resolve().is_relative_to(checkout):
            parser.error("Original and generated shaders must stay outside the Git checkout")
    report = adapt(args.source, args.output, args.host_camera, args.host_terrain_boundary, args.host_cloud_height,
                   args.host_cloud_foreground)
    args.output.with_suffix(".bindings.private.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("sourceSha256", "parametersByteSize", "pushByteSize")}))


if __name__ == "__main__":
    main()
