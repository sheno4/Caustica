# Caustica

Caustica is an experimental ray-traced renderer for Minecraft 26.2's Vulkan backend.
It replaces the vanilla world view with hardware ray tracing and NVIDIA DLSS
features while keeping Minecraft's familiar UI and gameplay intact.

Caustica is early software. Expect bugs, missing visual cases, and frequent
changes while the renderer is being built.

![Caustica ray-traced Minecraft scene](docs/gallery/2026-07-09_21.25.14.jpg)

## Links

- [Discord](https://discord.gg/SeWCjyKu2)
- [Modrinth](https://modrinth.com/mod/caustica)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/caustica/preview)
- [Gallery](docs/gallery.md)

## Features

- Vulkan hardware path-traced world rendering
- Vulkan 1.4 with unified image layouts, descriptor heaps, and shader-object compute/raster passes
- DLSS Ray Reconstruction support
- DLSS Super Resolution after NRD denoising
- DLSS Frame Generation support (experimental)
- HDR output
- Dynamic entity rendering in the ray-traced scene
- LabPBR-style material support
- SER (Shader Execution Reordering) support
- NRD denoising
- Procedural Nether and End skies
- RTXPT-style adaptive next-event estimation implemented independently for retained rectangle, spot, and distant lights

## Requirements

- **Vulkan graphics backend enabled**
- A GPU and driver with Vulkan ray tracing support
- NVIDIA RTX GPU and supported driver for DLSS features
- HDR-capable display and OS HDR mode for HDR output
- On Linux, an HDR-capable Wayland compositor and a native Wayland session for HDR output
- Install LabPBR resource pack like [SPBR](https://modrinth.com/resourcepack/spbr) for better visuals

## Installation

1. Install either Fabric Loader (with Fabric API) or NeoForge for Minecraft `26.2`.
2. Download the Caustica jar matching that loader and put it in your Minecraft `mods` folder.
3. Launch the game with the Vulkan graphics backend.
4. Open Video Settings to adjust Caustica's renderer options.

## Usage Notes

- Caustica is client-side only.
- DLSS Super Resolution, Ray Reconstruction and Frame Generation require supported NVIDIA
  hardware and drivers.
- On Linux if Minecraft crashes on startup with stack overflow errors, try adding `-Xss2M` to the Java args to increase the stack size.
- Use Java args to improve performance. Minecraft Launcher default:
  `-XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC`
- Frame Generation is experimental and disabled by default.
- HDR output requires an HDR swapchain and a correctly configured HDR display.
- When HDR is enabled on Linux, Caustica selects GLFW's native Wayland backend automatically. X11/XWayland surfaces generally do not expose the required HDR10/PQ format.
- If Minecraft falls back to OpenGL after a crash, re-enable the Vulkan backend
  before using Caustica again.

## Compatibility

Caustica takes over the world renderer, so other mods that heavily modify world
rendering, shader pipelines, post-processing, or the Vulkan backend may conflict.
UI-only mods are more likely to work.

## Status

Caustica is not a finished renderer yet. Current work focuses on visual
correctness, world coverage, stability, and making the SDR/HDR presentation
paths behave consistently.

## License

Caustica's project-owned source code and documentation are licensed under the
GNU Lesser General Public License v3.0 or later. See [LICENSE.md](LICENSE.md),
[COPYING](COPYING), and [COPYING.LESSER](COPYING.LESSER).

Release artifacts may bundle NVIDIA DLSS/NGX SDK components under NVIDIA's own
license terms and the Slang compiler under Apache 2.0 with LLVM Exception. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Development

See the [developer guide](docs/developer_guide.md) for build and launch instructions,
the [architectural commitments](docs/VISION.md) for the Minecraft-independent engine contract,
and the [debugging guide](docs/DEBUGGING.md) for runtime diagnostics.

## TODO List

- [ ] Weather and volumetric fog/clouds
- [ ] FSR upscaling for non-NVIDIA GPUs
- [ ] Opacity micromap acceleration
- [ ] LOD
- [ ] ReSTIR
