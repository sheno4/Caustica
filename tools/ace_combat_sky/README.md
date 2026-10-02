# Local ACE COMBAT 8 archive inspection

`inspect_install.py` reads IoStore headers and unencrypted directory indexes from a local installation. It writes only the requested report, outside the game installation. It neither starts the game nor extracts, decrypts, or modifies its assets. UTOC version 6 is the supported layout.

```powershell
python tools/ace_combat_sky/inspect_install.py --game-dir 'S:\SteamLibrary\steamapps\common\ACE COMBAT 8' --output '../analysis-game/preflight.private.json'
```

The default report contains archive metadata, header SHA-256 values, decoded flags, compression methods, readable file counts, extensions, and sky path candidate counts. `--include-paths` includes asset filenames for local inspection; keep that output private. A filename candidate does not establish an asset's contents or suitability for rendering.

The verified installation uses encrypted main containers and Oodle compression. Its unencrypted optional containers contain 356 `.uptnl` texture bulk files, with no sky path candidates. These files need the corresponding texture metadata from the main containers to interpret their pixel format and dimensions.

The installed executable contains Cloudly renderer and sky-parameter identifiers. The native Cloudly renderer cannot be imported into Caustica as a Minecraft shader. An actual port needs accessible sky parameters and cloud density assets, conversion into Caustica's environment and volume contracts, and rendering verification. No game assets or key material are included here.

The binary layouts are cross-checked against the upstream [CUE4Parse IoStore header](https://github.com/FabianFG/CUE4Parse/blob/master/CUE4Parse/UE4/IO/Objects/FIoStoreTocHeader.cs), [TOC resource](https://github.com/FabianFG/CUE4Parse/blob/master/CUE4Parse/UE4/IO/Objects/FIoStoreTocResource.cs), and [directory reader](https://github.com/FabianFG/CUE4Parse/blob/master/CUE4Parse/UE4/IO/IoStoreReader.cs).
