"""Create a bounded private sky pack from exact offline package exports.

Copies validated BC1 volume mips without decoding or interpreting channels.
Preserves serialized sky/component properties, including omitted defaults.
No game executable, DLL, key, process, or image-recognition operation is used.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_MIP_BYTES = 256 * 1024 * 1024
MAX_PACK_BYTES = 1024 * 1024 * 1024
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


def read_json(path):
    if path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("JSON input exceeds the 16 MiB bound")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def single_export(path, expected_type):
    records = read_json(path)
    if not isinstance(records, list) or len(records) != 1:
        raise ValueError("Expected exactly one cooked export")
    record = records[0]
    if record.get("Type") != expected_type:
        raise ValueError("Cooked export class does not match the requested input")
    return record


def source_data(record):
    value = record.get("Properties", {}).get("Data")
    if not isinstance(value, dict):
        raise ValueError("Cooked export has no parsed Data struct")
    return value


def schema_field(record, name):
    matches = [value for key, value in record.items()
               if key == name or re.fullmatch(re.escape(name) + r"\[\d+\]", key)]
    if len(matches) != 1:
        raise ValueError("Required native property is absent or ambiguous: " + name)
    return matches[0]


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def private_output(path):
    path = path.resolve()
    if any((directory / ".git").exists() for directory in (path, *path.parents)):
        raise ValueError("Private sky packs must remain outside Git checkouts")
    if path.exists() and any(path.iterdir()):
        raise ValueError("Output directory must be empty to avoid replacing a source pack")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sky-export", required=True, type=Path)
    parser.add_argument("--components-export", required=True, type=Path)
    parser.add_argument("--texture-exports", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output_root = private_output(args.output)
    texture_root = args.texture_exports.resolve()
    sky = single_export(args.sky_export, "CloudlySkyParams")
    volume = single_export(args.components_export, "CloudlyVolumeComponents")
    sky_parameters = source_data(sky)
    volume_data = source_data(volume)
    components = schema_field(volume_data, "ComponentsData")
    source_textures = schema_field(volume_data, "ComponentsTexture")
    if not isinstance(components, list) or not 1 <= len(components) <= 16384:
        raise ValueError("Component array is absent or exceeds its bound")
    if not isinstance(source_textures, list) or not 1 <= len(source_textures) <= 64:
        raise ValueError("Texture table is absent or exceeds its bound")

    summary = read_json(texture_root / "extraction-summary.private.json")
    results = summary.get("results", [])
    by_provider_path = {}
    for index, result in enumerate(results):
        provider_path = result.get("requestedPath")
        if provider_path in by_provider_path:
            raise ValueError("Duplicate source package in the extraction summary")
        by_provider_path[provider_path] = (index, result)

    textures = []
    copies = []
    pack_bytes = 0
    for texture_id, source in enumerate(source_textures):
        asset_path = source.get("AssetPathName", "")
        package_path = asset_path.split(".", 1)[0]
        if not package_path.startswith("/Cloudly/"):
            raise ValueError("Texture table references an unsupported package root")
        provider_path = "Engine/Plugins/Cloudly/Content/" + package_path[len("/Cloudly/"):] + ".uasset"
        if provider_path not in by_provider_path:
            raise ValueError("Referenced texture was not exported: " + asset_path)
        index, result = by_provider_path[provider_path]
        if result.get("packageError") or result.get("exportError"):
            raise ValueError("Referenced texture extraction reports an error")
        directory = texture_root / f"{index:03d}"
        native = read_json(directory / "native-volume.private.json")
        texture_export = single_export(directory / "exports.private.json", "VolumeTexture")
        if native.get("class") != "VolumeTexture" or native.get("format") != "PF_DXT1":
            raise ValueError("Only confirmed native BC1 volume textures are supported")
        expected_object = asset_path.rsplit(".", 1)[-1]
        if native.get("sourceObject") != expected_object or texture_export.get("Name") != expected_object:
            raise ValueError("Texture package object does not match its source table association")
        if native.get("firstMip") != 0:
            raise ValueError("A full mip chain beginning at level zero is required")
        top_shape = (native.get("sizeX"), native.get("sizeY"), native.get("sizeZ"))
        if not all(isinstance(d, int) and 0 < d <= 4096 for d in top_shape):
            raise ValueError("Native volume dimensions are invalid")
        native_mips = native.get("mips", [])
        if not 1 <= len(native_mips) <= 16:
            raise ValueError("Native mip array exceeds its bound")
        mips = []
        for level, mip in enumerate(native_mips):
            shape = tuple(mip.get(name) for name in ("sizeX", "sizeY", "sizeZ"))
            if mip.get("level") != level or shape != tuple(max(1, d >> level) for d in top_shape):
                raise ValueError("Native mip levels or dimensions are inconsistent")
            width, height, depth = shape
            expected_bytes = ((width + 3) // 4) * ((height + 3) // 4) * depth * 8
            if mip.get("bytes") != expected_bytes or expected_bytes > MAX_MIP_BYTES:
                raise ValueError("BC1 mip byte count is invalid or exceeds its bound")
            expected_hash = mip.get("sha256", "")
            if not SHA256_PATTERN.fullmatch(expected_hash):
                raise ValueError("Native mip SHA-256 is invalid")
            # The native helper produces these bounded names. Do not follow an
            # arbitrary absolute path embedded in private metadata.
            source_path = directory / f"native-volume-mip-{level:02d}.bc1.bin"
            if source_path.stat().st_size != expected_bytes or digest_file(source_path) != expected_hash:
                raise ValueError("Native mip bytes do not match their verified metadata")
            relative_path = f"textures/{texture_id:03d}/mip-{level:02d}.bc1.bin"
            copies.append((source_path, output_root / relative_path))
            mips.append({"level": level, "width": width, "height": height,
                         "depth": depth, "path": relative_path, "sha256": expected_hash})
            pack_bytes += expected_bytes
            if pack_bytes > MAX_PACK_BYTES:
                raise ValueError("Source pack exceeds the 1 GiB bound")
        textures.append({"textureId": texture_id, "sourceAsset": asset_path,
                         "format": "PF_DXT1", "mips": mips,
                         "serializedProperties": texture_export.get("Properties", {})})

    for component in components:
        if not isinstance(component, dict):
            raise ValueError("Component entry is not a native property object")
        texture_id = schema_field(component, "TextureId")
        if texture_id != 0xFFFFFFFF and not 0 <= texture_id < len(textures):
            raise ValueError("Component refers to an invalid texture table index")

    manifest = {
        "schemaVersion": 1,
        "source": {
            "game": "ACE COMBAT 8",
            "skyAsset": sky.get("Package"),
            "componentsAsset": volume.get("Package"),
            "skyExportSha256": digest_file(args.sky_export),
            "componentsExportSha256": digest_file(args.components_export),
            "engineParserSetting": summary.get("parserEngineSetting"),
            "actualEngineMinorVerified": summary.get("actualEngineMinorVerified", False),
            "nativeDefaultValuesIncluded": False,
            "componentPositionUnitsVerified": False,
            "cloudlyChannelSemanticsVerified": False,
            "renderingAlgorithmRecovered": False,
            "volumeLayout": "BC1 4x4 blocks, X block columns and Y block rows within each Z slice"
        },
        "skyParameters": sky_parameters,
        "components": components,
        "textures": textures
    }
    manifest_json = json.dumps(manifest, indent=2, allow_nan=False)
    output_root.mkdir(parents=True, exist_ok=True)
    for source_path, output_path in copies:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_path, output_path)
    manifest_path = output_root / "manifest.json"
    manifest_path.write_text(manifest_json, encoding="utf-8")
    print(json.dumps({"schemaVersion": 1, "components": len(components),
                      "textures": len(textures), "mips": len(copies),
                      "mipBytes": pack_bytes, "privateManifest": str(manifest_path)}))


if __name__ == "__main__":
    main()
