# Caustica Realistic Clouds fork

This fork adds a self-contained Nubis-style volumetric cumulus layer to Caustica's Vulkan ray-tracing renderer.

## What changed

- `shaders/pipelines/world/clouds.slang`
  - procedural weather field
  - height-dependent cumulus profile
  - multi-octave 3D density field
  - high-frequency erosion/detail
  - Beer-Lambert extinction
  - forward HG droplet phase approximation
  - direct sun/moon light marching
  - bounded multiple-scattering approximation
  - world-space cloud-shadow transmittance
- `shaders/pipelines/world/sky.rmiss.slang`
  - composites volumetric clouds over Caustica's Hillaire sky LUT, stars, sun and moon
- `shaders/pipelines/world/indirect.rgen.slang`
  - applies cloud transmittance to directional NEE so terrain/particles/SSS receive moving cloud shadows

The implementation is a clean-room Slang implementation based on the public Nubis/Horizon rendering literature and does not copy Alpha Piscium shader source or its texture assets.

## Quality constants

Edit `shaders/pipelines/world/clouds.slang`:

- `CLOUD_VIEW_STEPS = 96` — camera ray samples
- `CLOUD_LIGHT_STEPS = 8` — sun/moon samples per occupied camera sample
- `CLOUD_SHADOW_STEPS = 12` — direct-light shadow samples on world surfaces
- `CLOUD_BASE_KM = 1.15`
- `CLOUD_TOP_KM = 4.35`
- `CLOUD_COVERAGE = 0.56`

RTX 5090 cinematic starting point:

```text
CLOUD_VIEW_STEPS = 160
CLOUD_LIGHT_STEPS = 12
CLOUD_SHADOW_STEPS = 16
```

Do not raise these blindly: cloud cost scales roughly with `VIEW_STEPS * LIGHT_STEPS` for occupied samples.

## Build requirements

Caustica's upstream build compiles Slang to SPIR-V at build time. You need:

- Java 25
- Vulkan SDK containing `slangc` and `spirv-val`
- NVIDIA DLSS SDK matching Caustica's build expectations
- platform NGX shim binaries, or build them from `native/ngx_shim`

The upstream CI file `.github/workflows/ci.yml` is the canonical reproducible build reference.

Typical Windows PowerShell environment:

```powershell
$env:VULKAN_SDK = 'C:\VulkanSDK\1.4.350.0'
$env:DLSS_SDK = 'C:\dev\DLSS'
.\gradlew.bat build -PngxPlatforms=windows-x64 -PngxVendorConfig=rel -PngxShimConfig=release
```

The mod jar will be written under `build/libs/`.

## Current validation state

The source integration and Caustica ABI usage were reviewed in the provided source tree. This ChatGPT execution environment did not contain `slangc`, `spirv-val`, Java 25, or the DLSS SDK/native libraries, so a truthful runnable jar could not be produced here. Do not treat a jar assembled without newly compiled SPIR-V as valid; Caustica loads the generated shader binaries from the jar at runtime.
