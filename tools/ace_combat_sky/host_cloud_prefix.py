"""Add a physical-surface checkpoint to a privately supplied native cloud trace.

The source owns its integration and final shading. This transformation extracts
that source's finalizer and invokes it on the saved prefix and total states.
The declarations must enter the ordinary descriptor adaptation with the source.
"""

from __future__ import annotations

import re


HOST_CLOUD_PREFIX_BINDINGS = (
    ("HostPhysicalDistanceTexture", "Texture2D<float>"),
    ("HostCloudForegroundTexture", "RWTexture2D<float4>"),
    ("HostCheckerBoardForegroundTexture", "RWTexture2D<float4>"),
)
HOST_CLOUD_PREFIX_DECLARATIONS = "\n".join(
    f"{kind} {name};" for name, kind in HOST_CLOUD_PREFIX_BINDINGS
) + "\n"

# The finalizer consumes these accumulators. Values remain in native packed form
# so a checkpoint does not add quantization or reinterpret any scattering term.
_STATE_FIELDS = (
    ("TraceSkyOutput", "ret"),
    ("TracePosition", "trace_state"),
    ("float", "view_trace_main_iteration"),
    ("Packing_float_float_TO_uint_Sqrt", "packing_tAP_as_cloud_tr_integral_AND_tAP_as_cloud_tr_integral_temp"),
    ("Packing_uint_uint_bool_TO_uint_16_15_1", "packing_trace_iteration_raymarch_step_AND_tAP_continuous_density_count_AND_bCloudOpaque"),
    ("Packing_float3_float_TO_uint2", "packing_cloud_scattering_AND_prev"),
    ("Packing_float_float_TO_uint", "packing_SkyAmbientLight_AND_GroundIrradiance"),
    ("Packing_float_float_TO_uint", "packing_cloud_scattering_nodirection_prev_AND_cloud_scattering_EffectLight"),
    ("Packing_float3_float3_TO_uint3", "packing_prev_ap_xyz_and_prev_ap_w_AND_prev_tr"),
    ("Packing_float3_float3_TO_uint3", "packing_better_ap_col_and_delta_ap_col"),
    ("float", "distance_at_which_transmittance_got_very_small"),
    ("Packing_float_float_TO_uint", "packing_od_view_ray_cloud_AND_prev"),
)


def _braced_end(text: str, opening: int) -> int:
    cursor, depth = opening + 1, 1
    while depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    return cursor


def _function(text: str, name: str) -> tuple[int, int, int]:
    match = re.search(r"\b(?:void|TraceSkyOutput|TracePosition)\s+" + re.escape(name)
                      + r"\s*\([^{}]*\)\s*\{", text)
    if not match:
        raise ValueError("The native cloud checkpoint interface changed: " + name)
    opening = match.end() - 1
    return match.start(), opening, _braced_end(text, opening)


def _once(text: str, pattern: str, replacement: str, label: str) -> str:
    text, count = re.subn(pattern, lambda _: replacement, text, flags=re.S)
    if count != 1:
        raise ValueError("The native cloud checkpoint interface changed: " + label)
    return text


def _capture() -> str:
    return "HostCloudCaptureIntegral(" + ", ".join(name for _, name in _STATE_FIELDS) + ")"


def _helpers(finalizer: str) -> str:
    members = "\n".join(f"    {kind} {name};" for kind, name in _STATE_FIELDS)
    arguments = ",\n    ".join(f"{kind} {name}" for kind, name in _STATE_FIELDS)
    assignments = "\n".join(f"    state.{name} = {name};" for _, name in _STATE_FIELDS)
    locals_ = "\n".join(f"    {kind} {name} = state.{name};" for kind, name in _STATE_FIELDS)
    return ("struct HostCloudIntegralState\n{\n" + members + "\n};\n"
            "HostCloudIntegralState HostCloudCaptureIntegral(\n    " + arguments + ")\n{\n"
            "    HostCloudIntegralState state;\n" + assignments + "\n    return state;\n}\n"
            "TraceSkyOutput HostCloudFinalizeIntegral(HostCloudIntegralState state, "
            "const TraceSkyRanges ret1, const Ray view_ray, const MiscConstants cst)\n{\n"
            + locals_ + "\n    float tAP = 0.0f;\n" + finalizer + "\n    return ret;\n}\n")


def host_cloud_prefix_trace(text: str) -> str:
    """Return native62 with a single-march foreground scattering checkpoint.

    HostPhysicalDistanceTexture stores un-faded radial metres, using MaxDistance
    for environment pixels. Foreground outputs mirror the native color targets:
    pre-exposed native RGB scattering and mean RGB transmittance in alpha.
    Resource declarations and descriptor registration belong to the caller.
    """
    if not re.search(r"\bvoid\s+SkyTraceCS\s*\(", text):
        return text
    if "HostCloudIntegralState" in text:
        raise ValueError("The native cloud checkpoint was already applied")

    start, opening, end = _function(text, "TraceSkyFunction")
    body = text[opening + 1:end - 1]
    final = re.search(r"\bret\.dst\.view_steps\s*=\s*view_trace_main_iteration\s*;", body)
    if not final:
        raise ValueError("The native cloud finalization boundary changed")
    integration, finalizer = body[:final.start()], body[final.start():]
    integration = _once(integration, r"\bfloat\s+tAP\s*=\s*0\.0f\s*;", "", "finalizer depth")
    loop = re.search(r"\bfor\s*\([^{}]*\bview_trace_main_iteration\b[^{}]*\)\s*\{", integration)
    if not loop:
        raise ValueError("The native cloud integration loop changed")
    loop_end = _braced_end(integration, loop.end() - 1)
    checkpoint = ("\n        if (!hostCloudPrefixCaptured && trace_state.t >= hostCloudPhysicalDistance)\n"
                  "        {\n            hostCloudPrefixState = " + _capture() + ";\n"
                  "            hostCloudPrefixCaptured = true;\n"
                  "            max_steps_count += 1.0f;\n"
                  "            max_steps_count_with_empty += 1.0f;\n        }\n")
    integration = integration[:loop_end - 1] + checkpoint + integration[loop_end - 1:]
    # A physical boundary can split a native step. Only that ray receives one
    # additional iteration, after its checkpoint has consumed the partial step.
    integration = _once(integration,
                        r"\bconst\s+float\s+max_steps_count\s*=\s*float\(128\)\s*;",
                        "float max_steps_count = float(128);", "checkpoint view budget")
    integration = _once(integration,
                        r"\bconst\s+float\s+max_steps_count_with_empty\s*=\s*float\(256\)\s*;",
                        "float max_steps_count_with_empty = float(256);", "checkpoint empty budget")
    integration = _once(integration, r"\btrace_state\s*=\s*RaymarchStep\s*\(",
                        "trace_state = RaymarchStep(hostCloudPhysicalDistance, ", "checkpoint step argument")
    initialization = ("\n    const float hostCloudPhysicalDistance = "
                      "HostPhysicalDistanceTexture[Unpack(cst.packing_id)];\n"
                      "    HostCloudIntegralState hostCloudPrefixState;\n"
                      "    bool hostCloudPrefixCaptured = false;\n")
    finish = ("\n    HostCloudIntegralState hostCloudTotalState = " + _capture() + ";\n"
              "    ret = HostCloudFinalizeIntegral(hostCloudTotalState, ret1, view_ray, cst);\n"
              "    hostForegroundOutput = ret;\n"
              "    if (hostCloudPhysicalDistance <= Unpack_X(ret1.packing_tracable_cloud_range))\n"
              "    {\n        hostForegroundOutput = TraceSkyOutputInit();\n    }\n"
              "    else if (hostCloudPrefixCaptured)\n"
              "    {\n        TraceSkyRanges hostCloudPrefixRanges = ret1;\n"
              "        hostCloudPrefixRanges.first_occluder_depth = hostCloudPhysicalDistance;\n"
              "        hostForegroundOutput = HostCloudFinalizeIntegral(hostCloudPrefixState, "
              "hostCloudPrefixRanges, view_ray, cst);\n    }\n")
    signature = text[start:opening]
    signature = _once(signature, r"\)\s*$", ", out TraceSkyOutput hostForegroundOutput)\n", "trace output parameter")
    text = text[:start] + _helpers(finalizer) + signature + "{" + initialization + integration + finish + "}" + text[end:]

    step_start, step_opening, step_end = _function(text, "RaymarchStep")
    step = text[step_start:step_end]
    step = _once(step, r"\bRaymarchStep\s*\(",
                 "RaymarchStep(const float hostCloudPhysicalDistance, ", "step distance parameter")
    step = _once(step, r"\bint\s+i\s*=\s*0\s*;",
                 "if (trace_state.t < hostCloudPhysicalDistance) "
                 "next_stop_max = min(next_stop_max, hostCloudPhysicalDistance);\n\tint i = 0;",
                 "step physical boundary")
    text = text[:step_start] + step + text[step_end:]

    trace_start, trace_opening, trace_end = _function(text, "TraceSky")
    trace = text[trace_start:trace_end]
    signature_length = trace_opening - trace_start
    signature = _once(trace[:signature_length], r"\)\s*$",
                      ", out TraceSkyOutput hostForegroundOutput)\n", "entry output parameter")
    trace = signature + trace[signature_length:]
    brace = trace.index("{")
    trace = trace[:brace + 1] + "\n    hostForegroundOutput = TraceSkyOutputInit();" + trace[brace + 1:]
    trace = _once(trace, r"\bTraceSkyFunction\s*\(\s*ret\s*,\s*ret1\s*,\s*view_ray\s*,\s*cst\s*,\s*cst2\s*\)\s*;",
                  "TraceSkyFunction(ret, ret1, view_ray, cst, cst2, hostForegroundOutput);", "entry finalizer output")
    text = text[:trace_start] + trace + text[trace_end:]
    text = _once(text, r"\bconst\s+TraceSkyOutput\s+eval\s*=\s*TraceSky\s*\(\s*cst\s*,\s*cst2\s*,\s*ret1\s*\)\s*;",
                 "TraceSkyOutput hostForegroundOutput;\n"
                 "    const TraceSkyOutput eval = TraceSky(cst, cst2, ret1, hostForegroundOutput);\n"
                 "    const float4 hostForegroundColor = float4(hostForegroundOutput.dst.add * UnrealPreExposure, "
                 "GetAverage(hostForegroundOutput.dst.mul));", "dispatch foreground output")
    for native, host in (("CheckerBoardColorTexture", "HostCheckerBoardForegroundTexture"),
                         ("SkyTraceOutput_ColorTexture", "HostCloudForegroundTexture")):
        pattern = r"\b" + native + r"\[out_id\.xy\]\s*=\s*ResultColor\s*;"
        text = _once(text, pattern,
                     native + "[out_id.xy] = ResultColor;\n    " + host + "[out_id.xy] = hostForegroundColor;",
                     native + " foreground target")
    text = _once(text,
                 r"\bif\s*\(\s*bTileBasedHalfPixels\s*\)\s*"
                 r"CheckerBoardColorTexture\[uint2\(out_id\.x\s*\*\s*0\.5,\s*out_id\.y\)\]\s*=\s*0\s*;",
                 "if (bTileBasedHalfPixels)\n    {\n"
                 "        CheckerBoardColorTexture[uint2(out_id.x * 0.5, out_id.y)] = 0;\n"
                 "        HostCheckerBoardForegroundTexture[uint2(out_id.x * 0.5, out_id.y)] = 0;\n"
                 "    }",
                 "checker foreground clear")
    return text
