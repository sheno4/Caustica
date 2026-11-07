# AC8 Cloudly on main

This branch starts from `sheno4/Caustica` main commit `330acd2` and ports the native Cloudly implementation from `94bb0218`. It uses main's Vulkan descriptor sets, asynchronous GPU executor, world renderer and ACES 2.0 display pipeline.

The bundled Fabric build contains the Hangar, Mission 3 and Mission 11 scenes. Video Settings offers Chinese and English controls for enabling the sky, changing scenes, moving cloud layers from −16000 to +16000 blocks, and setting cloud distance from 1 to 200 km. The client command `/caustica cloudheight <blocks>` sets the same height offset; omit the value to query it. Scene preparation happens in the background. Menu changes queue behind the current preparation. A ready scene replaces the previous scene at a frame boundary only if it is still requested. Failed preparation releases its cached archive before a retry. Height edits settle for 250 ms before rebuilding cloud history, shadows and AO.

The native density bake, atmosphere bake, cloud trace, reconstruction and foreground-cloud composition use the original recipes. All 81 private programs are compiled to SPIR-V 1.5 with fixed descriptor-set bindings. The noise generator's seven pushed resource indices have an explicit binding mapping. Parameter and camera byte layouts retain their source offsets. Pipeline subgroup size is fixed at 32 and the device checks the required arithmetic, storage-image and subgroup features.

Cloud composition runs after world reconstruction and before exposure, bloom, ACES 2.0 mapping and frame generation. A separate physical first-hit depth image preserves cloud occlusion while the existing reconstruction depth continues to describe transmitted guide surfaces. Cloudy sky colors cross the BT.709/ACEScg boundary explicitly. The native cloud shadow volume attenuates matching sun or moon illumination at the receiving position. Finite lights retain the host lighting path. Automatic exposure for an active native sky has +8 EV headroom for physical moonlight.

Frame constants, views, pipelines and descriptor sets remain owned until their GPU use retires. Retired invocation descriptor sets are reusable. Preparation outputs publish after compute completion. Scene changes, cloud settings and world changes invalidate their associated temporal data. Camera motion uses double-precision frame times for its metres-per-second calculation; the native shader time fields retain their float layout. The cloud camera incorporates the eye translation from the level projection's view effects.

## Local bundle

Game-derived textures, programs and parameter templates stay outside the Git checkout. The local build reads a prepared descriptor-set bundle with the following property.

```powershell
./gradlew.bat jar -PngxPlatforms=windows-x64 '-PcloudlyBundleInputRoot=../analysis-game/bundle-main.1.private'
```

The build requires JDK 25, `SLANG_SDK`, `VULKAN_SDK`, NVIDIA DLSS SDK `310.9.16` (commit `9981c6350dc58c0ce21f024fd65e31e851c31689`) and an NGX shim compiled against that SDK. `SLANG_SDK` selects the build-time Slang compiler. SDK headers and Windows release DLL hashes are pinned in `native/ngx_shim/dlss-sdk.json` and checked during packaging. The JAR carries that manifest and NVIDIA's license alongside its native libraries. An additional installed sky pack is unnecessary.

Ray Reconstruction requests Preset F (RR2) by default. Existing `dlss-rr.preset=0` settings resolve to F; explicit D (`4`) and E (`5`) remain available. Every feature creation replaces all per-quality preset hints, so switching models cannot retain a previous feature's hints. Both Windows RR and Frame Generation libraries are version `310.9.16.0`. Frame Generation remains opt-in. Replace the mod only after the Minecraft JVM exits, as loaded native libraries remain held for its lifetime. Driver overrides can change the final model selected by NVIDIA's runtime.

The public source-only CI build has no private sky resources. Compilation and package checks cover Java, host shaders, all native programs, manifests, hashes and bindings. Game rendering, frame rate and visual agreement with AC8 require in-game acceptance. Compilation and package integrity alone do not establish runtime or visual correctness.

The Windows bundled artifact is `caustica-0.1.3-ac8-cloud.jar`. Its three scenes each contain 81 unique programs and retain their foreground-cloud history pair. Local compilation and package receipts, including artifact size and SHA-256, accompany the artifact outside the checkout.
