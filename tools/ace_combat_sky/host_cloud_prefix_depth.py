"""Keep the physical surface distance separate from the cloud LOD trace range."""
import re


def _end(text: str, opening: int) -> int:
    depth = 1
    cursor = opening + 1
    while depth:
        depth += (text[cursor] == "{") - (text[cursor] == "}")
        cursor += 1
    return cursor


def _function(text: str, signature: str) -> str:
    match = re.search(signature, text)
    if not match:
        raise ValueError("The physical cloud depth producer interface changed")
    opening = text.index("{", match.start())
    return text[match.start():_end(text, opening)]


def host_cloud_prefix_depth(text: str) -> str:
    if "void DownScaleDepthCS(" not in text:
        return text
    distance = _function(text, r"float\s+GetDistanceFromSceneDepth\s*\(")
    fade = re.search(r"if\(EnableTerrainFade && MaxVisibleDistance > 0\.0f\)\s*\{", distance)
    if not fade:
        raise ValueError("The cloud depth producer no longer separates physical and LOD distances")
    distance = distance[:fade.start()] + distance[_end(distance, fade.end() - 1):]
    distance = distance.replace("GetDistanceFromSceneDepth", "HostPhysicalSceneDistance")
    downscale = _function(text, r"void\s+DownScale\s*\(")
    downscale = downscale.replace("void DownScale(", "void HostDownScalePhysical(")
    downscale = downscale.replace("GetDistanceFromSceneDepth", "HostPhysicalSceneDistance")
    downscale = downscale.replace("DstTexture[id.xy] = final_distance;",
                                  "DstTexture[id.xy] = final_distance * MaxDistance;")
    call = "DownScale(DepthTexture, DownScaledDistanceTexture, depth_uv, id.xy, final_distance);"
    if text.count(call) != 1:
        raise ValueError("The cloud depth producer dispatch interface changed")
    text = text.replace(call, call + """
    if (all(id.xy < uint2(DownScaleOutputTextureSize)))
    {
        float hostPhysicalDistance;
        HostDownScalePhysical(DepthTexture, HostPhysicalDistanceTexture, depth_uv, id.xy, hostPhysicalDistance);
    }
""", 1)
    entry = re.search(r"\[WaveSize\(32\)\]\s*\[numthreads\([^]]*\)\]\s*void DownScaleDepthCS", text)
    if not entry:
        raise ValueError("The cloud depth entry annotation changed")
    return ("RWTexture2D<float> HostPhysicalDistanceTexture;\n" + text[:entry.start()]
            + distance + "\n" + downscale + "\n" + text[entry.start():])
