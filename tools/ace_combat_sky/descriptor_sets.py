"""Adapt private Cloudly resource handles to main's Vulkan descriptor sets."""
from __future__ import annotations

import re


NOISE_RESOURCES = (
    ("outputIndex", "RWTexture3D<float4>", 16),
    ("warpIndex", "RWTexture3D<float4>", 20),
    ("test2DIndex", "Texture2D<float4>", 24),
    ("test3DIndex", "Texture3D<float4>", 28),
    ("heightmapIndex", "Texture2D<float4>", 32),
    ("componentsIndex", "Texture3D<float>", 36),
    ("samplerIndex", "SamplerState", 40),
)


def descriptor_sets(text: str, program: dict) -> tuple[str, list[dict]]:
    """Preserve parameter offsets and algorithms while assigning fixed bindings."""
    declarations = []
    push_resources = []
    if program["id"] == "22":
        for slot, (name, kind, offset) in enumerate(NOISE_RESOURCES):
            heap = "Sampler" if kind == "SamplerState" else "Resource"
            expression = rf"{heap}DescriptorHeap\s*\[\s*privatePush\.{name}\s*\]"
            text, count = re.subn(expression, f"HostDescriptor{slot}", text)
            if not count:
                raise ValueError("Missing original noise resource: " + name)
            declarations.append(f"[[vk::binding({slot}, 0)]] {kind} HostDescriptor{slot};")
            push_resources.append(dict(name=name, slot=slot, byteOffset=offset, type=kind))
    else:
        for binding in program.get("bindings", []):
            slot = binding["byteOffset"] // 4
            kind = binding["type"]
            expression = (r"(?:Resource|Sampler)DescriptorHeap\s*\[\s*\(\(uint\s*\*\)"
                          r"originalPush\.bindingsAddress\)\s*\[\s*" + str(slot) + r"\s*\]\s*\]")
            text, count = re.subn(expression, f"HostDescriptor{slot}", text)
            if not count:
                raise ValueError("Missing original descriptor: " + program["id"] + "/" + binding["name"])
            declarations.append(f"[[vk::binding({slot}, 0)]] {kind} HostDescriptor{slot};")
    if "ResourceDescriptorHeap" in text or "SamplerDescriptorHeap" in text:
        raise ValueError("Unconverted original resource handle: " + program["id"])
    return "\n".join(declarations) + "\n" + text, push_resources
