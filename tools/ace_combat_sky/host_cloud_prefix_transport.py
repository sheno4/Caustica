"""Carry terrain-front cloud scattering through the native reconstruction chain.

The privately supplied program remains the authority for sample selection,
weights, history rejection and color clipping. This adapter adds a second affine
cloud value to those operations without embedding the private program bodies.
The four channels contain inscattering RGB and transmittance A.
"""

from __future__ import annotations

import re
from collections.abc import Callable


def _braced_end(text: str, opening: int) -> int:
    depth = 1
    cursor = opening + 1
    while depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    return cursor


def _rename(text: str, names: dict[str, str]) -> str:
    return re.sub(r"\b(?:" + "|".join(map(re.escape, names)) + r")\b",
                  lambda match: names[match[0]], text)


def _mirror_assignments(text: str, names: dict[str, str], targets: set[str],
                        companions: dict[str, Callable[[str], str]] | None = None) -> str:
    """Follow existing assignments inside their existing control-flow scopes."""
    symbols = "|".join(map(re.escape, sorted(targets, key=len, reverse=True)))
    pattern = re.compile(
        r"(?<![\w.])(?P<statement>(?:(?:const\s+)?(?:half4|float4)\s+)?"
        r"(?P<name>" + symbols + r")\b(?:\s*\[[^;\n]*\])?"
        r"(?:\s*\.[xyzwrgba]+)?\s*[+*/\-]?=(?!=)[^;]*;)"
    )

    def replace(match: re.Match) -> str:
        prefix = text[text.rfind("\n", 0, match.start()) + 1:match.start()]
        indent = re.match(r"[ \t]*", prefix)[0]
        companion = ((companions or {}).get(match["name"]) or
                     (lambda statement: _rename(statement, names)))(match[0])
        return match[0] + "\n" + indent + companion

    return pattern.sub(replace, text)


def _mirror_shared_array(text: str) -> str:
    pattern = re.compile(r"groupshared\s+half4\s+GroupSharedArrayColor\s*\[[^;]+;")
    text, count = pattern.subn(lambda match: match[0] + "\n" + match[0].replace(
        "GroupSharedArrayColor", "HostGroupSharedForeground"), text)
    if count != 1:
        raise ValueError("The native cloud shared-color array interface changed")
    return text


def _checker(text: str) -> str:
    names = {
        "CheckerBoardColorTexture": "HostCheckerBoardForegroundTexture",
        "RWResolvedColorTexture": "HostRWResolvedForegroundTexture",
    }
    for name in re.findall(r"\b(?:sum|resolved|total|min|max)_inscatter\b|\binscatter_[ABCD]\b", text):
        names[name] = "HostForeground_" + name
    targets = set(names) - {"CheckerBoardColorTexture"}
    text = _mirror_assignments(text, names, targets)
    # The fallback trims extrema per total-color channel. Its companion must
    # retain those same samples even when foreground extrema occur elsewhere.
    fallback = re.compile(r"HostForeground_resolved_inscatter\s*=\s*0\.5\s*\*[^;]+;")
    text, count = fallback.subn(
        "HostForeground_resolved_inscatter = HostCheckerForegroundMiddle("
        "inscatter_A, inscatter_B, inscatter_C, inscatter_D, "
        "HostForeground_inscatter_A, HostForeground_inscatter_B, "
        "HostForeground_inscatter_C, HostForeground_inscatter_D);", text)
    if count != 1:
        raise ValueError("The native checker fallback interface changed")
    helper = """
float4 HostCheckerForegroundMiddle(float4 a, float4 b, float4 c, float4 d,
                                   float4 fa, float4 fb, float4 fc, float4 fd)
{
    float4 result;
    for (uint channel = 0; channel < 4; channel++)
    {
        float ranks[4] = {a[channel], b[channel], c[channel], d[channel]};
        float values[4] = {fa[channel], fb[channel], fc[channel], fd[channel]};
        for (uint i = 1; i < 4; i++)
            for (uint j = i; j > 0 && ranks[j] < ranks[j - 1]; j--)
            {
                float rank = ranks[j]; ranks[j] = ranks[j - 1]; ranks[j - 1] = rank;
                float value = values[j]; values[j] = values[j - 1]; values[j - 1] = value;
            }
        result[channel] = 0.5f * (values[1] + values[2]);
    }
    return result;
}
"""
    return helper + text + "\nTexture2D<float4> HostCheckerBoardForegroundTexture;\nRWTexture2D<float4> HostRWResolvedForegroundTexture;\n"


def _prefilter(text: str) -> str:
    text = _mirror_shared_array(text)
    names = {
        "SkyTraceOutput_ColorTexture": "HostTraceForegroundTexture",
        "RWFilteredColorTexture": "HostRWFilteredForegroundTexture",
        "GroupSharedArrayColor": "HostGroupSharedForeground",
        "color_sample": "HostForegroundSample",
        "color_current": "HostForegroundCurrent",
    }
    text = _mirror_assignments(text, names, set(names) - {"SkyTraceOutput_ColorTexture"})
    return text + "\nTexture2D<float4> HostTraceForegroundTexture;\nRWTexture2D<float4> HostRWFilteredForegroundTexture;\n"


def _temporal_bounds(text: str) -> str:
    function = re.search(r"bool\s+GetNeighborhoodBoundingboxes\s*\(", text)
    if not function:
        raise ValueError("The native temporal neighborhood interface changed")
    opening = text.index("{", function.end())
    end = _braced_end(text, opening)
    body = text[function.start():end]
    arguments = re.compile(r"out\s+half4\s+c_min\s*,\s*out\s+half4\s+c_max\s*,")
    body, count = arguments.subn(lambda match: match[0] +
        " out half4 HostForegroundMin, out half4 HostForegroundMax,", body)
    if count != 1:
        raise ValueError("The native temporal neighborhood outputs changed")
    body = _mirror_assignments(body, {"c_min": "HostForegroundMin", "c_max": "HostForegroundMax"},
                               {"c_min", "c_max"})
    sample = re.search(r"half4\s+current_value\s*=\s*GroupSharedArrayColor\s*\[", body)
    if not sample:
        raise ValueError("The native temporal neighborhood color sample changed")
    block_start = body.rfind("{", 0, sample.start())
    block_end = _braced_end(body, block_start)
    block = body[block_start:block_end]
    bounding_step = re.search(
        r"GetNeighborhoodBoundingboxStep\s*\(\s*c_min\s*,\s*c_max\s*,"
        r"\s*current_value\s*,\s*1\s*\)\s*;", block)
    if not bounding_step:
        raise ValueError("The native temporal neighborhood color bounds changed")
    companion = _rename(block[1:bounding_step.start()], {
        "current_value": "HostForegroundNeighborhoodValue",
        "GroupSharedArrayColor": "HostGroupSharedForeground",
    })
    paired_step = (companion +
        "HostForegroundBoundingboxStep(HostForegroundMin, HostForegroundMax, "
        "c_min, c_max, current_value, HostForegroundNeighborhoodValue);\n")
    block = block[:bounding_step.start()] + paired_step + block[bounding_step.start():]
    body = body[:block_start] + block + body[block_end:]
    text = text[:function.start()] + body + text[end:]
    call = re.compile(r"GetNeighborhoodBoundingboxes\s*\(\s*color_min\s*,\s*color_max\s*,")
    text, count = call.subn(lambda match: match[0] + " HostForegroundMin, HostForegroundMax,", text)
    if count != 1:
        raise ValueError("The native temporal neighborhood invocation changed")
    return text


_TEMPORAL_HELPERS = """
half4 HostForegroundSelectMin(half4 a, half4 b, half4 fa, half4 fb)
{
    half4 result;
    for (uint channel = 0; channel < 4; channel++)
        result[channel] = b[channel] < a[channel] ? fb[channel] : fa[channel];
    return result;
}
half4 HostForegroundSelectMax(half4 a, half4 b, half4 fa, half4 fb)
{
    half4 result;
    for (uint channel = 0; channel < 4; channel++)
        result[channel] = b[channel] > a[channel] ? fb[channel] : fa[channel];
    return result;
}
void HostForegroundBoundingboxStep(inout half4 foregroundMin,
                                   inout half4 foregroundMax,
                                   half4 totalMin, half4 totalMax,
                                   half4 total, half4 foreground)
{
    half4 rankedTotal = half4(QGAMES_RGB2YCoCg(total.rgb), total.a);
    half4 rankedForeground = half4(QGAMES_RGB2YCoCg(foreground.rgb), foreground.a);
    foregroundMin = HostForegroundSelectMin(totalMin, rankedTotal, foregroundMin, rankedForeground);
    foregroundMax = HostForegroundSelectMax(totalMax, rankedTotal, foregroundMax, rankedForeground);
}
half4 HostForegroundClip(half4 foreground, half4 total,
                        half4 totalMin, half4 totalMax,
                        half4 foregroundMin, half4 foregroundMax)
{
    half4 rankedTotal = half4(QGAMES_RGB2YCoCg(total.rgb), total.a);
    half4 rankedForeground = half4(QGAMES_RGB2YCoCg(foreground.rgb), foreground.a);
    for (uint channel = 0; channel < 4; channel++)
    {
        if (rankedTotal[channel] < totalMin[channel])
            rankedForeground[channel] = foregroundMin[channel];
        else if (rankedTotal[channel] > totalMax[channel])
            rankedForeground[channel] = foregroundMax[channel];
    }
    return QGAMES_YCoCg2RGB(rankedForeground);
}
"""


_TEMPORAL_BOUNDS_EXPANSION = re.compile(
    r"(?P<bound>color_(?P<kind>min|max))\s*=\s*lerp\(\s*(?P=bound)\s*,"
    r"\s*(?P=kind)\(\s*(?P=bound)\s*,\s*OutputColor\s*\)\s*,(?P<weight>[^;]+)\)\s*;"
)


def _paired_temporal_clamps(entry: str) -> str:
    """Retain total-cloud values before their in-place clamping updates."""
    entry, count = _TEMPORAL_BOUNDS_EXPANSION.subn(lambda match:
        "half4 HostTotal" + match["kind"].title() + "BeforeExpansion = " +
        match["bound"] + ";\n" + match[0], entry)
    if count != 2:
        raise ValueError("The native temporal bounds expansion changed")
    output = re.compile(
        r"OutputColor\s*=\s*QGAMES_YCoCg2RGB\(clip\(QGAMES_RGB2YCoCg\(OutputColor\),"
        r"\s*color_min\s*,\s*color_max\s*\)\)\s*;"
    )
    entry, count = output.subn(lambda match:
        "half4 HostTotalBeforeOutputClamp = OutputColor;\n" + match[0], entry)
    if count != 1:
        raise ValueError("The native temporal output clipping changed")
    return entry


def _foreground_bounds_expansion(statement: str) -> str:
    match = _TEMPORAL_BOUNDS_EXPANSION.fullmatch(statement)
    if not match:
        raise ValueError("The native temporal bounds expansion changed")
    kind = match["kind"].title()
    foreground = "HostForeground" + kind
    return (foreground + " = lerp(" + foreground + ", HostForegroundSelect" + kind +
            "(HostTotal" + kind + "BeforeExpansion, OutputColor, " + foreground +
            ", HostForegroundOutput), " + match["weight"].strip() + ");")


def _temporal(text: str) -> str:
    text = _mirror_shared_array(text)
    text = _temporal_bounds(text)
    # This luma-derived factor belongs to the total cloud. Compute it once so
    # the foreground follows exactly the same amount of history clipping.
    history_clip = re.compile(
        r"HistoryColor\s*=\s*lerp\(\s*HistoryColor\s*,\s*ClampedHistoryColor\s*,(?P<weight>[^;]+)\)\s*;"
    )
    text, count = history_clip.subn(lambda match:
        "half HostHistoryClampWeight = " + match["weight"].strip() + ";\n"
        "HistoryColor = lerp(HistoryColor, ClampedHistoryColor, HostHistoryClampWeight);", text)
    if count != 1:
        raise ValueError("The native temporal history clipping factor changed")
    # Preserve the scope of the source's optional one-line debug assignment.
    inline = re.compile(r"(?m)^(?P<indent>[ \t]*)(?P<condition>if\s*\([^\n]+?\))\s*(?P<assignment>OutputColor[^;]+;)\s*$")
    text = inline.sub(lambda match: match["indent"] + match["condition"] + "\n" +
        match["indent"] + "{\n" + match["indent"] + "    " + match["assignment"] +
        "\n" + match["indent"] + "}", text)
    names = {
        "SkyTraceOutput_ColorTexture": "HostTraceForegroundTexture",
        "FilteredColorTexture": "HostFilteredForegroundTexture",
        "HistoryColorTexture": "HostHistoryForegroundTexture",
        "RWResolvedColorTexture": "HostRWResolvedForegroundTexture",
        "GroupSharedArrayColor": "HostGroupSharedForeground",
        "color_sample": "HostForegroundSample",
        "ColorCurrentFiltered": "HostForegroundCurrentFiltered",
        "ColorCurrentUpscaled": "HostForegroundCurrentUpscaled",
        "HistoryColor": "HostForegroundHistory",
        "ClampedHistoryColor": "HostClampedForegroundHistory",
        "OutputColor": "HostForegroundOutput",
        "color_min": "HostForegroundMin",
        "color_max": "HostForegroundMax",
    }
    targets = set(names) - {"SkyTraceOutput_ColorTexture", "FilteredColorTexture", "HistoryColorTexture"}
    # Neighborhood bounds have already been expanded inside their function.
    function = re.search(r"void\s+TemporalUpscaleCS\s*\(", text)
    if not function:
        raise ValueError("The native temporal entry point changed")
    before, entry = text[:function.start()], text[function.start():]
    before = _mirror_assignments(before, names, {"color_sample", "GroupSharedArrayColor"})
    entry = _paired_temporal_clamps(entry)

    def output_companion(statement: str) -> str:
        if re.search(r"QGAMES_YCoCg2RGB\s*\(\s*clip\s*\(", statement):
            return ("HostForegroundOutput = HostForegroundClip(HostForegroundOutput, "
                    "HostTotalBeforeOutputClamp, color_min, color_max, "
                    "HostForegroundMin, HostForegroundMax);")
        return _rename(statement, names)

    entry = _mirror_assignments(entry, names, targets, {
        "ClampedHistoryColor": lambda statement:
            "half4 HostClampedForegroundHistory = HostForegroundClip(HostForegroundHistory, "
            "HistoryColor, color_min, color_max, HostForegroundMin, HostForegroundMax);",
        "OutputColor": output_companion,
        "color_min": _foreground_bounds_expansion,
        "color_max": _foreground_bounds_expansion,
    })
    declaration = re.compile(r"half4\s+color_min\s*,\s*color_max\s*,[^;]+;")
    entry, count = declaration.subn(lambda match: match[0] +
        "\n    half4 HostForegroundMin, HostForegroundMax;", entry)
    if count != 1:
        raise ValueError("The native temporal color bounds declaration changed")
    # NaN recovery uses the same current sample while independently guarding
    # the companion channels before they enter persistent history.
    output = re.compile(r"HostRWResolvedForegroundTexture\s*\[\s*id\.xy\s*\]\s*=\s*HostForegroundOutput\s*;")
    entry, count = output.subn(lambda match:
        "if (hasnan(HostForegroundOutput)) HostForegroundOutput = HostForegroundCurrentUpscaled;\n" + match[0], entry)
    if count != 1:
        raise ValueError("The native temporal output interface changed")
    return _TEMPORAL_HELPERS + before + entry + "\nTexture2D<half4> HostTraceForegroundTexture;\nTexture2D<half4> HostFilteredForegroundTexture;\nTexture2D<half4> HostHistoryForegroundTexture;\nRWTexture2D<half4> HostRWResolvedForegroundTexture;\n"


def host_cloud_prefix_transport(text: str) -> str:
    """Extend checker, prefilter and temporal programs with their cloud prefix."""
    if re.search(r"\bvoid\s+CheckerBoardResolveCS\s*\(", text):
        return _checker(text)
    if re.search(r"\bvoid\s+TemporalUpscalePreFilterCS\s*\(", text):
        return _prefilter(text)
    if re.search(r"\bvoid\s+TemporalUpscaleCS\s*\(", text):
        return _temporal(text)
    return text
