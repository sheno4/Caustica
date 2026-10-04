"""Adapt a privately extracted HLSL compute program to Caustica's Vulkan ABI.

Input and generated shaders belong in a private source pack outside the checkout.
The adapter preserves function bodies, constant byte offsets and resource types.
It does not bundle original source or infer omitted runtime parameter values.
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
    ray = re.search(r"Ray\s+GetViewRay\s*\(.*?\n\}", text, re.S)
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
    text = re.sub(r"^#(?:line|pragma).*?$", "", text, flags=re.M)
    # Slang resolves structure declarations irrespective of source order.
    text = re.sub(r"(?m)^\s*struct\s+\w+\s*;\s*$", "", text)
    # DXC accepts these implicit conversions and constant folds integer max in array extents.
    text = re.sub(r"max\(\s*(\d+)\s*,\s*(\d+)\s*\)", lambda m: str(max(int(m[1]), int(m[2]))), text)
    text = text.replace("lerp(similarity > 0.15, simWeight,", "lerp(float4(similarity > 0.15), simWeight,")
    text = re.sub(r"uint16_t\s+width,\s*height;(?=\s*tex.GetDimensions)", "uint width, height;", text)
    if host_camera:
        text = host_camera_body(text)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(text, encoding="utf-8")
    return dict(schemaVersion=1, sourceSha256=hashlib.sha256(source_path.read_bytes()).hexdigest(),
                sourceBytes=source_path.stat().st_size, commonByteSize=common_size, commonFields=common,
                parametersByteSize=cursor, uniformBlocks=uniforms, bindings=bindings,
                pushByteSize=24 if host_camera else 16,
                pushFields=[dict(name="parametersAddress", offset=0, size=8),
                            dict(name="bindingsAddress", offset=8, size=8)] +
                           ([dict(name="cameraAddress", offset=16, size=8)] if host_camera else []),
                mathematicalFunctionsChanged=False,
                cameraBridge=dict(enabled=host_camera, byteSize=80 if host_camera else 0,
                                  sourceAxes="host X, negative host Z, host Y",
                                  replacedInterfaces=(["GetNormalizeRayWorldDirection", "GetViewRay origin"] +
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
