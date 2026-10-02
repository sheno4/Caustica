# Offline sky resource exporter

This tool reads local Unreal Engine PAK/IoStore containers as files. It exports
only exact requested paths, native package metadata, and available texture mip
bytes. It does not start the game, inspect a running process, load a game DLL,
convert bitmaps, or perform image recognition.

The repository contains exporter source only. Keep the key, recovered property
schemas, request lists, and exported game resources in a private directory outside
the repository. Both export stages reject an output directory inside a Git checkout.

## Build

Requirements: .NET 10 SDK, Git, and Python 3 for native mip recovery. The initial
local build used .NET SDK 10.0.100. Rust and an Unreal Editor installation are not
required.

CUE4Parse is an external source checkout, pinned to
`34f1a223e942b67e381329983fb7713f62f9ef9d` under Apache-2.0. Clone it outside this
repository. Set `$cue` and `$exporter` to your local source directories:

```powershell
$cue = 'C:\local-tools\CUE4Parse'
$exporter = 'C:\local-projects\Caustica\tools\ace_combat_sky\exporter'
git clone --filter=blob:none --no-checkout https://github.com/FabianFG/CUE4Parse.git $cue
git -C $cue fetch --depth 1 origin 34f1a223e942b67e381329983fb7713f62f9ef9d
git -C $cue checkout --detach 34f1a223e942b67e381329983fb7713f62f9ef9d
dotnet build "$exporter\OfflineSkyExtract.csproj" -c Release "-p:Cue4ParseSource=$cue" -p:CUE4PARSE_SKIP_NATIVE=true --nologo -clp:ErrorsOnly
```

The managed Oodle implementation is OodleSharp 0.0.1, MIT, with source pinned to
[`716d8b9ce3bd74ece37097d2b5e5ef67ef0c48c5`](https://github.com/NotOfficer/OodleSharp/tree/716d8b9ce3bd74ece37097d2b5e5ef67ef0c48c5).
CUE4Parse's native CMake build is skipped. This exporter never calls the Oodle
initialization methods that can download native binaries.

## Request and key files

The private key file accepts either one object or an array containing that object:

```json
{"key": "<64 hexadecimal characters>"}
```

The private request file is a JSON array of 1–64 exact provider paths. Remove an
archive's leading `../../../` from the paths:

```json
[
  "Engine/Plugins/Cloudly/Content/Textures/CloudParts/VT_Cumulus_Humilis_01.uasset",
  "Engine/Plugins/Cloudly/Content/CloudsGenerationParamsPresets/CGP_Default.uasset"
]
```

## Export

Set `$private` to a local directory outside every Git checkout. The engine argument
is an explicit parser setting. It is recorded as such and is not presented as a
verified game engine minor version. The inspected AC8 packages omit native
versioning information; their padded data-resource table uses the UE 5.4 or later
layout. Use `GAME_UE5_4` for that observed layout:

```powershell
$private = 'C:\private-game-data\ace-combat-sky'
$paks = 'S:\SteamLibrary\steamapps\common\ACE COMBAT 8\Game\Content\Paks'
dotnet "$exporter\bin\Release\net10.0\OfflineSkyExtract.dll" $paks "$private\validated-key.private.json" "$private\requests.private.json" "$private\exports" GAME_UE5_4
```

To decode unversioned properties, append the path to an exact local `.usmap` or
recovered JSON mapping file. Missing mappings are reported in the private summary;
raw extraction and native class/header inspection remain available.

JSON mappings use the CUE4Parse schema:

```json
{
  "structs": [
    {
      "name": "ExactRecoveredType",
      "superType": "ExactRecoveredSuperType",
      "propertyCount": 1,
      "properties": [
        {
          "index": 0,
          "name": "ExactRecoveredProperty",
          "arraySize": 1,
          "mappingType": {"type": "FloatProperty"}
        }
      ]
    }
  ],
  "enums": []
}
```

Supply exact serialized property indexes, own-type property counts, inheritance,
static array dimensions, and recursive property types. Native C++ byte offsets
cannot substitute for these schema indexes. The provider does not invent missing
fields or substitute estimated values.

An enum entry uses `{"name":"ExactEnum","values":["Member0","Member1"]}`
when its recovered values are contiguous and begin at zero. Member names are
unqualified; CUE4Parse adds the enum name. Retain transient fields in schema
indexes and remove only fields confirmed as editor-only. A successfully decoded
preset still omits properties not serialized in the cooked asset. Missing fields
are not evidence that their original native defaults were zero.

Each requested path gets a numbered output directory. The private summary records
the source path, byte counts, SHA-256 hashes, native class descriptors, extraction
errors, and parsed textures when mappings are available. The console prints counts
and sanitized errors, and never prints the key. An exit code of zero confirms at
least one raw file was extracted; consult the summary for per-file and property
decoding results.

## Recover native volume mip data

For a package whose native header confirms one `VolumeTexture` export and an
inline `PF_DXT1` platform payload:

```powershell
python "$exporter\extract_native_volume.py" "$private\exports\000"
```

This stage reads the exported package and native header. It verifies the bulk table,
inline byte offsets, mip dimensions, and BC1 block sizes before writing raw mip
files and metadata. It does not need proprietary property mappings. The current
implementation supports inline DXT1 volume textures only and leaves Cloudly's
channel semantics explicitly unverified. It cannot reconstruct the original
rendering algorithm from textures alone.

## Create a local source pack

After parsing a sky preset and its companion volume-components asset, export
only the texture packages referenced by `ComponentsTexture`. Recover the native
mips in each texture output directory with the preceding command. This avoids
substituting an unrelated texture for one used by the preset.

```powershell
python "$exporter\create_source_pack.py" --sky-export "$private\presets\002\exports.private.json" --components-export "$private\presets\003\exports.private.json" --texture-exports "$private\textures" --output "$private\source-pack"
```

The output directory must be empty and outside every Git checkout. The converter
reads the texture extraction summary, matches every native object to the preset's
texture table, and validates every mip's dimensions, BC1 byte count, and SHA-256.
The bounds are 64 textures, 16 mips per texture, 256 MiB per mip, 1 GiB total mip
bytes, and 16 MiB per JSON input.

`manifest.json` uses schema version 1 with `source`, `skyParameters`, `components`,
and `textures`. Sky/component fields retain their exact serialized names and
values. Each texture carries its original numeric `textureId`, source asset name,
format, and mip levels with dimensions, relative file paths, and hashes. The pack
contains only referenced raw BC1 mips and metadata; it has no absolute game paths
or keys. It does not decode channel values or assign units to unlabeled fields.
Native defaults and the original Cloudly rendering algorithm remain separate
evidence requirements.

CUE4Parse can emit repeated JSON property names for native static arrays. The
converter preserves every occurrence as an ordered JSON array under the original
name, instead of silently retaining only the last value. Source metadata records
this encoding.

An optional `--renderer-adapter` input adds explicit implementation choices as
`rendererAdapter`. That JSON object must contain `"target":"Caustica"` and
`"approximation":true`. Its settings do not alter the original serialized
properties or the source verification flags. Treat channel selection, coordinate
units, scale meaning, and axis conversion supplied here as adapter assumptions
until separate native evidence verifies them.
