"""Recover validated inline native DXT1 volume mips, without property schemas.

Reads already exported private package/header files. Never converts, displays,
or recognizes images. Does not interpret proprietary Cloudly scalar properties.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct


def native_object_name(raw, header):
    exports = header["exports"]
    # A mapped FName has a base-name index and a numeric suffix. Read the
    # exact fields from the native export map so suffixes such as _16 survive.
    export_map_offset, bundle_offset = struct.unpack_from("<ii", raw, 32)
    if bundle_offset - export_map_offset != 72 or not 0 <= export_map_offset <= len(raw) - 72:
        raise ValueError("Expected one bounded native Zen export-map entry")
    serial_offset, serial_size, mapped_name, number = struct.unpack_from("<QQII", raw, export_map_offset)
    if serial_offset != exports[0]["serialOffset"] or serial_size != exports[0]["serialSize"]:
        raise ValueError("Native export-map offsets differ from the parsed header")
    names = header["names"]
    if mapped_name >> 30 or not mapped_name < len(names):
        raise ValueError("Expected a package-scoped native object name")
    return names[mapped_name] + (f"_{number - 1}" if number else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_directory", type=Path)
    args = parser.parse_args()
    args.package_directory = args.package_directory.resolve()
    if any((directory / ".git").exists() for directory in (args.package_directory, *args.package_directory.parents)):
        raise ValueError("Private native volume exports must remain outside Git checkouts")
    raw = (args.package_directory / "payload_00.uasset").read_bytes()
    header = json.loads((args.package_directory / "native-header.private.json").read_text())
    exports = header["exports"]
    if len(exports) != 1 or exports[0]["className"] != "VolumeTexture":
        raise ValueError("Expected one confirmed native VolumeTexture export")
    object_name = native_object_name(raw, header)
    layouts = header["bulkLayouts"]
    if len(layouts) != 1:
        raise ValueError("Expected exactly one structurally validated native bulk table")
    bulk_map = layouts[0]["entries"]
    start = header["headerSize"] + exports[0]["serialOffset"]
    export_end = start + exports[0]["serialSize"]
    formats = list(re.finditer(rb"PF_DXT1\x00", raw[start : min(export_end, start + 4096)]))
    if len(formats) != 1:
        raise ValueError("Expected one native PF_DXT1 platform descriptor")
    format_offset = start + formats[0].start()
    x, y, packed, string_length = struct.unpack_from("<IIII", raw, format_offset - 16)
    z = packed & 0x3FFFFFFF
    if string_length != 8 or packed & 0xE0000000 or not all(0 < dimension <= 4096 for dimension in (x, y, z)):
        raise ValueError("Unsupported or invalid native platform descriptor")
    cursor = format_offset + 8
    first_mip, mip_count = struct.unpack_from("<ii", raw, cursor)
    cursor += 8
    if not 0 < mip_count <= 16 or mip_count != len(bulk_map):
        raise ValueError("Mip count does not match the native bulk map")
    mip_records = []
    for level in range(mip_count):
        bulk_index = struct.unpack_from("<i", raw, cursor)[0]
        cursor += 4
        if bulk_index != level:
            raise ValueError("Unexpected native mip bulk-data index")
        bulk = bulk_map[bulk_index]
        data_offset = start + bulk["serialOffset"]
        size = bulk["serialSize"]
        if bulk["flags"] != 72 or data_offset != cursor or cursor + size + 12 > export_end:
            raise ValueError("Mip is not validated contiguous inline native bulk data")
        data = raw[cursor : cursor + size]
        cursor += size
        mx, my, mz = struct.unpack_from("<iii", raw, cursor)
        cursor += 12
        expected_shape = (max(1, x >> level), max(1, y >> level), max(1, z >> level))
        if (mx, my, mz) != expected_shape:
            raise ValueError("Mip dimensions do not follow the platform descriptor")
        expected_bytes = ((mx + 3) // 4) * ((my + 3) // 4) * mz * 8
        if size != expected_bytes:
            raise ValueError("Native DXT1 mip byte size does not match its 3D block layout")
        mip_path = args.package_directory / f"native-volume-mip-{level:02d}.bc1.bin"
        mip_path.write_bytes(data)
        mip_records.append({"level": level, "sizeX": mx, "sizeY": my, "sizeZ": mz,
                            "bytes": size, "sourceOffset": data_offset,
                            "sha256": hashlib.sha256(data).hexdigest(), "path": str(mip_path)})
    result = {"sourceObject": object_name, "class": "VolumeTexture",
              "format": "PF_DXT1", "blockFormat": "BC1", "blockWidth": 4,
              "blockHeight": 4, "bytesPerBlock": 8, "firstMip": first_mip,
              "sizeX": x, "sizeY": y, "sizeZ": z, "mips": mip_records,
              "cloudlyChannelSemantics": "unverified; no property schema was supplied"}
    output = args.package_directory / "native-volume.private.json"
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"nativeClass": "VolumeTexture", "format": "PF_DXT1", "sizeX": x,
                      "sizeY": y, "sizeZ": z, "mips": mip_count, "privateMetadata": str(output)}))


if __name__ == "__main__":
    main()
