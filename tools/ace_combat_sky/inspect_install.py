"""Inspect local UE IoStore metadata without modifying game files."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct


MAGIC = b"-==--==--==--==-"
HEADER_SIZE = 144
INVALID_HANDLE = 0xFFFFFFFF
FLAG_NAMES = {1: "compressed", 2: "encrypted", 4: "signed", 8: "indexed", 16: "on_demand"}
SKY_PATH_TERMS = ("cloudly", "/sky/", "/cloud/", "cirrus", "cumulus", "stratus", "atmosphere")


def uint32(data: bytes, position: int) -> int:
    return struct.unpack_from("<I", data, position)[0]


def read_directory(index: bytes) -> list[str]:
    position = 0

    def read_string() -> str:
        nonlocal position
        count = struct.unpack_from("<i", index, position)[0]
        position += 4
        length = count if count >= 0 else -count * 2
        value = index[position:position + length]
        position += length
        return value.decode("utf-8" if count >= 0 else "utf-16le").rstrip("\0")

    mount_point = read_string()
    directory_count = uint32(index, position)
    position += 4
    directories = [struct.unpack_from("<IIII", index, position + i * 16) for i in range(directory_count)]
    position += directory_count * 16
    file_count = uint32(index, position)
    position += 4
    files = [struct.unpack_from("<III", index, position + i * 12) for i in range(file_count)]
    position += file_count * 12
    string_count = uint32(index, position)
    position += 4
    strings = [read_string() for _ in range(string_count)]

    pending = [(0, mount_point)]
    paths = []
    while pending:
        directory, parent = pending.pop()
        name, child, sibling, first_file = directories[directory]
        prefix = parent + (strings[name] + "/" if name != INVALID_HANDLE else "")
        if sibling != INVALID_HANDLE:
            pending.append((sibling, parent))
        if child != INVALID_HANDLE:
            pending.append((child, prefix))
        file = first_file
        while file != INVALID_HANDLE:
            name, next_file, _toc_entry = files[file]
            paths.append(prefix + strings[name])
            file = next_file
    return sorted(paths)


def inspect_archive(path: Path, include_paths: bool) -> dict:
    with path.open("rb") as archive:
        header = archive.read(HEADER_SIZE)
        if len(header) != HEADER_SIZE or header[:16] != MAGIC:
            raise ValueError(f"Invalid IoStore header: {path.name}")
        if header[16] != 6:
            raise ValueError(f"Unsupported UTOC version {header[16]}: {path.name}; this reader supports version 6")
        if uint32(header, 20) != HEADER_SIZE or uint32(header, 32) != 12:
            raise ValueError(f"Unsupported IoStore structure sizes: {path.name}")

        entries = uint32(header, 24)
        block_count = uint32(header, 28)
        method_count = uint32(header, 36)
        method_length = uint32(header, 40)
        directory_size = uint32(header, 48)
        flags = header[80]
        position = HEADER_SIZE + entries * 22
        position += uint32(header, 84) * 4 + uint32(header, 96) * 4
        position += block_count * 12
        archive.seek(position)
        method_data = archive.read(method_count * method_length)
        methods = [method_data[i * method_length:(i + 1) * method_length].split(b"\0")[0].decode("ascii") for i in range(method_count)]
        if flags & 4:
            signature_size = struct.unpack("<I", archive.read(4))[0]
            archive.seek(2 * signature_size + 20 * block_count, 1)

        result = {
            "archive": path.name,
            "archive_size": path.stat().st_size,
            "header_sha256": hashlib.sha256(header).hexdigest(),
            "utoc_version": header[16],
            "flags": [name for bit, name in FLAG_NAMES.items() if flags & bit],
            "encrypted": bool(flags & 2),
            "toc_entries": entries,
            "compression_methods": methods,
            "compression_block_size": uint32(header, 44),
            "directory_size": directory_size,
        }
        if not directory_size:
            result["directory_status"] = "absent"
        elif flags & 2:
            result["directory_status"] = "encrypted_requires_key"
        else:
            paths = read_directory(archive.read(directory_size))
            candidates = [path for path in paths if any(term in path.lower() for term in SKY_PATH_TERMS)]
            result.update(
                directory_status="readable",
                readable_file_count=len(paths),
                file_extensions=sorted({Path(path).suffix for path in paths}),
                sky_candidate_count=len(candidates),
            )
            if include_paths:
                result.update(files=paths, sky_candidates=candidates)
        return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--include-paths", action="store_true", help="Include private asset filenames in the local report")
    args = parser.parse_args()
    game_dir = args.game_dir.resolve(strict=True)
    output = args.output.resolve()
    if output.is_relative_to(game_dir):
        parser.error("--output must be outside the game installation")

    archive_dir = game_dir / "Game" / "Content" / "Paks"
    archives = sorted(archive_dir.glob("*.utoc"))
    if not archives:
        parser.error(f"No .utoc archives found under {archive_dir}")
    report = {
        "schema_version": 1,
        "inspection": "read_only_metadata",
        "includes_private_paths": args.include_paths,
        "archives": [inspect_archive(path, args.include_paths) for path in archives],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for archive in report["archives"]:
        print(f"{archive['archive']}: {archive['directory_status']}, {archive['toc_entries']} entries, {', '.join(archive['compression_methods']) or 'uncompressed'}")
        if archive["directory_status"] == "readable":
            print(f"  {archive['readable_file_count']} readable files; {archive['sky_candidate_count']} sky path candidates")
    print(f"Report: {output}")


if __name__ == "__main__":
    main()
