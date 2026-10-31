"""Adapt private atmosphere/cloud composition to retain the physical-depth prefix.

The native shader bodies are supplied by the private source pack. This module
only extracts their existing background evaluation and adds the host composition
identity; it does not distribute those bodies or introduce a second dispatch.
"""

from __future__ import annotations

import re


def _once(text: str, before: str, after: str) -> str:
    if text.count(before) != 1:
        raise ValueError("The native cloud-prefix composition interface changed")
    return text.replace(before, after, 1)


def _block_end(text: str, opening: int) -> int:
    depth = 1
    cursor = opening + 1
    while depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    return cursor


def _function(text: str, pattern: str) -> tuple[int, int, int]:
    matches = list(re.finditer(pattern, text))
    if len(matches) != 1:
        raise ValueError("The native cloud-prefix function interface changed")
    match = matches[0]
    opening = text.index("{", match.start(), match.end())
    return match.start(), opening, _block_end(text, opening)


def _atmosphere_background(text: str) -> str:
    start, opening, end = _function(
        text, r"void\s+GetCompositedSceneColor\s*\([^)]*\)\s*\{"
    )
    body = text[opening + 1:end - 1]
    loop = re.search(
        r"for\s*\(\s*int\s+i\s*=\s*0\s*;\s*i\s*<\s*"
        r"\(bLowRate\s*\?\s*4\s*:\s*1\)\s*;\s*i\+\+\s*\)\s*\{",
        body,
    )
    if not loop:
        raise ValueError("The native atmosphere low-rate composition loop changed")
    loop_opening = body.index("{", loop.start(), loop.end())
    loop_end = _block_end(body, loop_opening)
    prefix = body[:loop.start()]
    pixel = body[loop_opening + 1:loop_end - 1]
    suffix = body[loop_end:]

    # Low-rate native composition shares its atmosphere sample among four pixels.
    # Keep that sample position while evaluating disks/stars at each output pixel.
    prefix = re.sub(r"\bid\b", "hostAtmosphereSampleId", prefix)
    prefix, count = re.subn(
        r"const\s+float\s+depth\s*=\s*SceneDepthTexture\.SampleLevel\([^;]+;",
        "const float depth = 0.0f;",
        prefix,
    )
    if count != 1:
        raise ValueError("The native atmosphere scene-depth sample changed")
    pixel, count = re.subn(
        r"if\(bLowRate\)\s*id\.xy\s*=\s*ids\[i\]\s*;", "", pixel
    )
    if count != 1:
        raise ValueError("The native atmosphere low-rate pixel selection changed")
    pixel, count = re.subn(
        r"const\s+float4\s+SceneColorFinal\s*=\s*RWSceneColorTexture\[id\.xy\]\s*;",
        "const float4 SceneColorFinal = float4(0, 0, 0, 0);",
        pixel,
    )
    if count != 1:
        raise ValueError("The native atmosphere surface-color sample changed")
    pixel = _once(pixel, "RWSceneColorTexture[id] = result;", "return result.rgb;")
    background = (
        "float3 HostCloudSharedBackground(uint2 hostAtmosphereSampleId, uint2 id)\n{"
        + prefix + pixel + suffix + "}\n\n"
    )

    body = "\n    const uint2 hostAtmosphereSampleId = id;" + body
    body = _once(
        body,
        "RWSceneColorTexture[id] = result;",
        "HostAtmosphereBackground[id] = float4(\n"
        "                HostCloudSharedBackground(hostAtmosphereSampleId, id),\n"
        "                depth > 0.0f ? pow2(terrain_blend_factor) : 0.0f);\n"
        "    RWSceneColorTexture[id] = result;",
    )
    return (
        text[:start]
        + "RWTexture2D<float4> HostAtmosphereBackground;\n\n"
        + background + text[start:opening + 1] + body + "}" + text[end:]
    )


def _foreground_reconstruction(text: str) -> str:
    start, opening, end = _function(
        text, r"ResColor\s+ReconstructClouds\s*\([^)]*\)\s*\{"
    )
    body = text[opening + 1:end - 1]
    edge = re.search(
        r"if\s*\(\s*InputParams\.bEdge\s*&&\s*\(!InputParams\.bskip\)\s*\)\s*\{",
        body,
    )
    if not edge:
        raise ValueError("The native cloud reconstruction edge interface changed")
    edge_opening = body.index("{", edge.start(), edge.end())
    edge_end = _block_end(body, edge_opening)
    edge_body = body[edge_opening + 1:edge_end - 1]
    sample = re.search(r"if\s*\(\s*!bInFrontOfClouds\s*\)\s*\{", edge_body)
    if not sample:
        raise ValueError("The native cloud reconstruction edge sampling changed")
    sample_opening = edge_body.index("{", sample.start(), sample.end())
    sample_end = _block_end(edge_body, sample_opening)
    # Physical-prefix transmittance owns surface occlusion. A total-cloud mean
    # distance cannot reject a ray that contains scattering before the surface.
    body = (body[:edge_opening + 1]
            + edge_body[sample_opening + 1:sample_end - 1]
            + body[edge_end - 1:])
    statement = re.compile(
        r"(?m)^(?P<indent>[ \t]*)(?P<kind>float4[ \t]+)?"
        r"(?P<name>col_inscatter|traced_value_offset[0-3])"
        r"(?P<tail>[ \t]*(?:=[^;]*;|;))"
    )
    mirrored = 0

    def mirror(match: re.Match[str]) -> str:
        nonlocal mirrored
        mirrored += 1
        name = match["name"].replace("col_inscatter", "host_foreground")
        name = name.replace("traced_value_offset", "host_foreground_offset")
        tail = match["tail"].replace(
            "SkyTraceOutput_ColorTexture", "HostCloudForegroundTexture"
        ).replace("traced_value_offset", "host_foreground_offset")
        return (
            match[0] + "\n" + match["indent"] + (match["kind"] or "")
            + name + tail
        )

    body = statement.sub(mirror, body)
    if mirrored < 10:
        raise ValueError("The native cloud reconstruction sampling interface changed")
    body = _once(
        body, "res.col_inscatter      = col_inscatter;",
        "res.col_inscatter      = col_inscatter;\n"
        "    res.host_foreground = host_foreground;",
    )
    text = text[:opening + 1] + body + text[end - 1:]
    result = re.search(r"struct\s+ResColor\s*\{", text)
    if not result:
        raise ValueError("The native reconstructed cloud result changed")
    result_end = _block_end(text, result.end() - 1)
    text = text[:result_end - 1] + "    float4 host_foreground;\n" + text[result_end - 1:]
    return text


_COMPOSITE_FUNCTION = """
Texture2D<float4> HostCloudForegroundTexture;
Texture2D<float4> HostAtmosphereBackgroundTexture;

float4 HostCompositeCloudPrefix(float4 scene, float4 foreground, float4 total,
                                float4 background, float transmittanceScale,
                                float scatteringScale)
{
    foreground.rgb *= scatteringScale;
    total.rgb *= scatteringScale;
    foreground.a = lerp(1.0f, foreground.a, transmittanceScale);
    total.a = lerp(1.0f, total.a, transmittanceScale);
    const float behindWeight = 1.0f - saturate(background.a);
    const float3 color = foreground.rgb + foreground.a * scene.rgb
        + behindWeight * ((total.rgb - foreground.rgb)
                         + (total.a - foreground.a) * background.rgb);
    return float4(color, scene.a * lerp(foreground.a, total.a, behindWeight));
}

"""


def _cloud_composition(text: str) -> str:
    text = _foreground_reconstruction(text)
    marker = "void ReconstructCloudsOutput("
    if text.count(marker) != 1:
        raise ValueError("The native cloud output function changed")
    text = text.replace(marker, _COMPOSITE_FUNCTION + marker, 1)
    text = _once(
        text,
        "float4 SceneColor = RWReconstructedCloudsTexture[InputParameters.ThreadId];",
        "float4 SceneColor = RWReconstructedCloudsTexture[InputParameters.ThreadId];\n"
        "    const float4 hostBackground = HostAtmosphereBackgroundTexture[InputParameters.ThreadId];",
    )
    text = _once(
        text,
        "col_cloud_pixel = lerp(SceneColor, SceneColor * res.col_inscatter.w, composite_adjust_factor_tr) + float4(res.col_inscatter.xyz, 0) * composite_adjust_factor_col;",
        "col_cloud_pixel = HostCompositeCloudPrefix(SceneColor, res.host_foreground,\n"
        "            res.col_inscatter, hostBackground, composite_adjust_factor_tr, composite_adjust_factor_col);",
    )
    text, count = re.subn(
        r"col_cloud_pixel\s*=\s*SceneColor\s*\*\s*res\.col_inscatter\.w\s*"
        r"\+\s*float4\(res\.col_inscatter\.xyz,\s*0\)\s*;",
        "col_cloud_pixel = HostCompositeCloudPrefix(SceneColor, res.host_foreground,\n"
        "            res.col_inscatter, hostBackground, 1.0f, 1.0f);",
        text,
    )
    if count != 1:
        raise ValueError("The native cloud scattering composition changed")
    text, count = re.subn(
        r"if\(\s*InputParameters\.bFarThanDepthClouds\s*&&\s*"
        r"InputParameters\.terrain_blend_factor\s*<\s*1\.0\s*\)\s*"
        r"col_cloud_pixel\.xyz\s*=\s*lerp\([^;]+;",
        "", text,
    )
    if count != 1:
        raise ValueError("The native whole-cloud terrain fade changed")
    text = _once(
        text,
        "col_cloud_pixel.rgb = lerp(col_cloud_pixel.rgb, SceneColor.rgb,",
        "const float3 hostCloudFadeBaseline = InputParameters.DeviceZ > 0.0f\n"
        "            ? HostCompositeCloudPrefix(SceneColor, res.host_foreground,\n"
        "                res.host_foreground, hostBackground, composite_adjust_factor_tr,\n"
        "                composite_adjust_factor_col).rgb\n"
        "            : SceneColor.rgb;\n"
        "        col_cloud_pixel.rgb = lerp(col_cloud_pixel.rgb, hostCloudFadeBaseline,",
    )
    return text


def host_cloud_prefix_composite(text: str) -> str:
    """Add naked resource declarations before the adapter registers heap bindings."""
    if re.search(r"\bvoid\s+GetCompositedSceneColor\s*\(", text):
        return _atmosphere_background(text)
    if re.search(r"\bvoid\s+ReconstructCloudsOutput\s*\(", text):
        return _cloud_composition(text)
    return text
