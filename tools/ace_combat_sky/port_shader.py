"""Adapt a privately extracted HLSL compute program to Caustica's Vulkan ABI.

Input and generated shaders belong in a private source pack outside the checkout.
The adapter preserves the original cloud scattering and uniform byte layouts.
Explicit host adaptations bridge cameras, tracing controls and missing-terrain
atmosphere boundaries. Original source and runtime resources stay private.
"""

from __future__ import annotations

import argparse
import hashlib
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
        # Missing Minecraft terrain reads the direction-dependent native air continuation.
        # Real surface depth and the later cloud composition remain unchanged.
        horizon_extension = """
        if (depth == 0.0f && RayIntersectGround(rAndMub.x, rAndMub.y))
        {
            float2 continuationUv;
            ATMViewLutParamsToUv(true, rAndMub.y, view_ray.d, h, GetRBot(), ATMViewLutSizeAndInvSize, continuationUv);
            const float3 continuationRadiance = ATMViewLut.SampleLevel(SkyTraceOutputTextureSampler, saturate(continuationUv), 0) * UnrealPreExposure;
            AtmColor = lerp(continuationRadiance * fog.a + fog.rgb * UnrealPreExposure, continuationRadiance, _blend_factor);
        }
        """
        marker = "float4 SceneColorWithAtm = 0;"
        if text.count(marker) != 1:
            raise ValueError("The native atmosphere composition boundary changed")
        text = text.replace(marker, horizon_extension + marker, 1)
        for radicand in (
            "ViewHeight * ViewHeight - BottomRadius * BottomRadius",
            "h * h - GetRBot() * GetRBot()",
            "dot(view_ray.o - GetEarthCenter(), view_ray.o - GetEarthCenter()) - GetRBot() * GetRBot()",
        ):
            text = text.replace("sqrt(" + radicand + ")", "sqrt(max(0.0f, " + radicand + "))")
        # The sky-side tangent maps to the common horizon row, including float rounding at the boundary.
        text = text.replace("Coord = sqrt(Coord);", "Coord = sqrt(max(0.0f, Coord));")
    return text


def host_environment_depth(text: str) -> str:
    """A zero reverse-depth value denotes environment, with normalized far distance one."""
    function = re.search(r"float\s+GetDistanceFromSceneDepth\s*\(\s*float2\s+uv\s*,\s*float\s+DeviceZ\s*,\s*float\s+h_real\s*,\s*float\s+tan_dist\s*\)\s*\{", text)
    if function:
        text = text[:function.end()] + "\n    if (DeviceZ <= 0.0f) return MaxDistance;" + text[function.end():]
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


def host_background_lut(text: str) -> str:
    """Missing terrain receives direction-dependent native air transport, not virtual planet radiance."""
    if re.search(r"\bvoid\s+ATMViewLutCS\s*\(", text):
        text = re.sub(r"\s*if\s*\(SumGodrayFlag\s*>\s*0\s*&&\s*EnableHighQualityGodRays\s*>\s*0\)\s*\{\s*RWATMViewLut\[id\.xy\]\s*=\s*0\.0;\s*return;\s*\}", "", text, count=1)
        function = re.search(r"ResultSingleScattering\s+IntegrateSingleScattering\s*\(", text)
        if not function:
            raise ValueError("The native atmosphere integration interface changed")
        start = text.index("{", function.end())
        cursor, depth = start + 1, 1
        while depth:
            if text[cursor] == "{":
                depth += 1
            elif text[cursor] == "}":
                depth -= 1
            cursor += 1
        body = text[start:cursor]
        distance = "trace_distance = min(trace_distance, trace_distance_max);"
        if body.count(distance) != 1:
            raise ValueError("The native atmosphere integration range changed")
        # AP's horizon bias and ground-range padding define a host boundary policy for absent
        # terrain. The ray, atmosphere coefficients and source quadrature remain native.
        body = body.replace(distance, distance + """
    const bool hostAirContinuation = tBot >= 0.0f;
    float hostGroundWeight = 0.0f;
    if (hostAirContinuation)
    {
        const float hostTangentDistance = sqrt(max(0.0f, h * h - rBot * rBot));
        const float hostHorizonBias = lerp(0.1f, 0.7f, smoothstep(0.0f, 5000.0f, h - rBot));
        const float hostHorizonFactor = sqrt(smoothstep(0.0f, max(1.0e-6f, hostTangentDistance * hostHorizonBias), tBot));
        hostGroundWeight = 1.0f - hostHorizonFactor;
        const float hostFarDistance = min(tTop, trace_distance_max);
        const float hostGroundPadding = lerp(5000.0f, 50000.0f, saturate(tBot / max(1.0e-6f, hostTangentDistance * 0.2f)));
        const float hostFiniteDistance = min(hostFarDistance, tBot + hostGroundPadding);
        trace_distance = lerp(hostFarDistance, hostFiniteDistance, hostGroundWeight);
    }
""", 1)
        sample = "float3 p_i = view_ray.o + t * view_ray.d;"
        if body.count(sample) != 1:
            raise ValueError("The native atmosphere sampling position changed")
        body = body.replace(sample, sample + """
        if (hostAirContinuation)
        {
            const float3 hostRadial = p_i - earth_center;
            const float hostRadius = length(hostRadial);
            if (hostRadius < rBot + 0.3f)
                p_i = earth_center + hostRadial * ((rBot + 0.3f) / max(hostRadius, 1.0e-6f));
        }
""", 1)
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
        text = text.replace("sqrt(ViewHeight * ViewHeight - GetRBot() * GetRBot())",
                            "sqrt(max(0.0f, ViewHeight * ViewHeight - GetRBot() * GetRBot()))")
    return text


def adapt(source_path: Path, output_path: Path, host_camera: bool = False) -> dict:
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
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(text, encoding="utf-8")
    return dict(schemaVersion=1, sourceSha256=hashlib.sha256(source_path.read_bytes()).hexdigest(),
                sourceBytes=source_path.stat().st_size, commonByteSize=common_size, commonFields=common,
                parametersByteSize=cursor, uniformBlocks=uniforms, bindings=bindings,
                pushByteSize=24 if host_camera else 16,
                pushFields=[dict(name="parametersAddress", offset=0, size=8),
                            dict(name="bindingsAddress", offset=8, size=8)] +
                           ([dict(name="cameraAddress", offset=16, size=8)] if host_camera else []),
                mathematicalFunctionsChanged=host_camera, viewTraceRangesCapped=view_trace_ranges,
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
    args = parser.parse_args()
    checkout = Path(__file__).resolve().parents[2]
    for path in (args.source, args.output):
        if path.resolve().is_relative_to(checkout):
            parser.error("Original and generated shaders must stay outside the Git checkout")
    report = adapt(args.source, args.output, args.host_camera)
    args.output.with_suffix(".bindings.private.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("sourceSha256", "parametersByteSize", "pushByteSize")}))


if __name__ == "__main__":
    main()
