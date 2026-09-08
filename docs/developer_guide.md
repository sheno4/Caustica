# Developer Guide

Initialize the pinned NRD source subrepository after cloning:

```bash
git submodule update --init third_party/NRD
```

The Gradle native build uses `third_party/NRD` by default and verifies its exact revision. `-PnrdSdk` or
`NRD_SDK` may point at another checkout for local integration testing, but the same pinned revision is required.

With CMake 3.30 or newer installed, prepare NRD's pinned native build dependencies once:

```bash
./gradlew :packages:nvidia-nrd:prepareNrdNativeDependencies
```

Use `gradlew.bat` in PowerShell. Preparation downloads the exact dependency revisions and DXC archive;
existing checkouts are reused and revision-checked. Pass `--offline` to require populated local
dependencies. Native configuration and compilation remain offline and do not run preparation implicitly.

## Windows

1. Install the Vulkan SDK from <https://vulkan.lunarg.com/sdk/home>.
   The installer sets `VULKAN_SDK` automatically.
2. Download Slang 2026.14.1 from <https://github.com/shader-slang/slang/releases/tag/v2026.14.1>,
   extract it, and set `SLANG_SDK` to the extracted directory.
3. Download the DLSS SDK from <https://github.com/NVIDIA/DLSS/releases>.
   Extract it, then set `DLSS_SDK` to the folder you extracted.

   To set it permanently for your Windows user account, run PowerShell with:

   ```powershell
   [Environment]::SetEnvironmentVariable("DLSS_SDK", "C:\path\to\dlss-sdk", "User")
   ```

   Restart your terminal after setting it. To set it only for the current
   PowerShell session, use:

   ```powershell
   $env:DLSS_SDK = "C:\path\to\dlss-sdk"
   ```

4. Configure and build the native shims:

```powershell
cmake -S packages/nvidia-ngx/native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release --config Release
cmake -S packages/slang-runtime/native -B build/cmake/slang_shim -G "Visual Studio 17 2022" -A x64
cmake --build build/cmake/slang_shim --config Release
```

5. Run the Fabric client:

```powershell
.\gradlew.bat --no-daemon -Ploader=fabric -PoptimizedClientJvm=true runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
```

NeoForge uses the same shared sources and has its own run configuration:

```powershell
.\gradlew.bat --no-daemon -Ploader=neoforge -PoptimizedClientJvm=true runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
```

The `runClient.ps1` helper defaults to Fabric. Pass `neoforge` to select the
NeoForge run configuration:

```powershell
.\runClient.ps1 neoforge
```

## Linux

Set `DLSS_SDK`, `SLANG_SDK`, and `VULKAN_SDK` before configuring CMake:

```bash
export DLSS_SDK=/path/to/dlss-sdk
export SLANG_SDK=/path/to/slang-2026.14.1
export VULKAN_SDK=/path/to/vulkan-sdk
```

`DLSS_SDK` must contain the NGX headers and static library. `VULKAN_SDK` must
contain Vulkan headers.

Then configure and build the native shims:

```bash
cmake -S packages/nvidia-ngx/native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
cmake -S packages/slang-runtime/native -B build/cmake/slang_shim -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/slang_shim
```

On NixOS, enter the development shell from `flake.nix` instead of setting up
the toolchain by hand:

```bash
nix develop
cmake -S packages/nvidia-ngx/native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
cmake -S packages/slang-runtime/native -B build/cmake/slang_shim -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/slang_shim
```

## Native Bundling

Gradle builds the NRD shim and bundles it with NGX natives and the Slang shared-library compiler
runtime for the current host platform by default:

```bash
./gradlew -Ploader=fabric build
```

Select NeoForge with `-Ploader=neoforge`. The build emits a loader-specific
classifier (`-fabric` or `-neoforge`); install the jar matching the instance's loader.

Release builds that already have both platform shims available can request a
cross-platform native bundle:

```bash
./gradlew -Ploader=fabric build -PngxPlatforms=windows-x64,linux-x64
```

Cross-platform Slang packaging accepts prepared runtime directories produced by
CI. Each platform directory contains `causticaslang`, the matching Slang shared
libraries, and the Slang standard module:

```bash
./gradlew -Ploader=fabric build \
  -PslangPlatforms=windows-x64,linux-x64 \
  -PslangRuntimeInputRoot=build/slang-runtime-input
```

For NRD, run `:packages:nvidia-nrd:bundleNrdNative` on each target platform after dependency preparation.
Merge the resulting `packages/nvidia-nrd/build/generated/nrd-natives` trees into one directory,
preserving `caustica/natives/nrd/<revision>/<platform>/` and its library and license files. Package that
directory with `-PnrdNativeInputRoot=build/nrd-runtime-input`; this skips the local NRD native build.
CI uses this path to include both Windows and Linux runtimes in each loader artifact.

For local compiler development, `-Dcaustica.slang.path=/path/to/runtime` loads
an unpacked runtime directory instead of extracting the bundled copy.

The `causticaslang` ABI is version 2. Its dynamic program operation accepts an
engine module/entry point and a pack module/concrete type. Slang loads both from
the session search paths, specializes the engine-owned generic entry point with
the pack type, links, reflects, validates, and emits engine-owned SPIR-V. Pack
API 0.1 appearance modules contain no shader entry points or pipeline
declarations. This restriction is scoped to the current appearance contract:
the planned pack-compute extension will compile manifest-declared compute entry
points through a separate engine-owned wrapper, reflection validator, and frame-
graph path. It will not expose integrator, direct-lighting, Vulkan scheduling, or
engine-service selection to packs.

Ray-pack manifests are validated against the bundled Draft 2020-12 schema at
`caustica/raypacks/api/0.1/pack.schema.json` before typed parsing. The schema is
strict about unknown fields; semantic checks that require engine or Slang state
remain separate.

Validate the Java FFM boundary and the engine/pack specialization path with:

```bash
./gradlew validateRayPackContract test --tests dev.comfyfluffy.caustica.slang.SlangLibraryTest
```

Run the Vulkan RT/DLSS-RR client with:

```bash
nvidia-offload ./gradlew --no-daemon -Ploader=fabric -PoptimizedClientJvm=true runClient --args='--renderDebugLabels --graphicsBackend VULKAN'
```
