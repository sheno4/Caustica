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
- DLSS Ray Reconstruction support
- DLSS Frame Generation support (experimental)
- HDR output
- Dynamic entity rendering in the ray-traced scene
- LabPBR-style material support
- OMM (Opacity Micro-Map) + SER (Shader Execution Reordering) optimizations

## Requirements

- **Vulkan graphics backend enabled**
- A GPU and driver with Vulkan ray tracing support
- NVIDIA RTX GPU and supported driver for DLSS features
- HDR-capable display and OS HDR mode for HDR output
- On Linux, an HDR-capable Wayland compositor and a native Wayland session for HDR output
- Install LabPBR resource pack like [SPBR](https://modrinth.com/resourcepack/spbr) for better visuals

## Installation

1. Install Fabric Loader for Minecraft `26.2`.
2. Install Fabric API.
3. Put the Caustica jar in your Minecraft `mods` folder.
4. Launch the game with the Vulkan graphics backend.
5. Open Video Settings to adjust Caustica's renderer options.

## Usage Notes

- Caustica is client-side only.
- DLSS Ray Reconstruction and Frame Generation require supported NVIDIA
  hardware and drivers.
- On Linux if Minecraft crashes on startup with stack overflow errors, try adding `-Xss2M` to the Java args to increase the stack size.
- Use Java args to improve performance. Minecraft Launcher default:
  `-XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC`
- Frame Generation is experimental and needs to be enabled by modifying the configuration file.
- HDR output requires an HDR swapchain and a correctly configured HDR display.
- When HDR is enabled on Linux, Caustica selects GLFW's native Wayland backend automatically. X11/XWayland surfaces generally do not expose the required HDR10/PQ format.
- If Minecraft falls back to OpenGL after a crash, re-enable the Vulkan backend
  before using Caustica again.

## Compatibility

Caustica takes over the world renderer, so other mods that heavily modify world
rendering, shader pipelines, post-processing, or the Vulkan backend may conflict.
UI-only mods are more likely to work.

### Vivecraft

The Vivecraft integration targets Minecraft 26.2 on Fabric with Vivecraft
26.2-1.3.15 and the Vulkan backend. With Fast or Fancy graphics, Caustica
composites the left and right eye world colors before Vivecraft draws its
hands and spatial UI. Each eye has independent motion, jitter and DLSS Ray
Reconstruction history. Vivecraft retains raster depth for overlay occlusion
and submits the eye textures to the headset.

The headset path uses SDR and disables DLSS Frame Generation while VR is
active. Auxiliary views, including the scope and third-person camera, and the
Fabulous transparency path use Vivecraft's native rendering. The desktop
Video Settings screen exposes Caustica's renderer settings.

Toggle **Fixed Foveated Rendering** in the VR Rendering section of Video Settings
to render each eye with a lower-resolution full view and a higher-resolution
center. Each layer has its own DLSS Ray Reconstruction history. Caustica blends
the layers before exposure and bloom and sends the full-size result to
Vivecraft. Both insets use the headset's angular center and each eye's own
position. **Wide Resolution Scale** sets both axes of the wide layer from 25%
to 100% of the eye target. **Inset Resolution Limit** caps the center layer's
square side at 256 to 4096 pixels, up to the eye target's shorter edge.
**Center Coverage Angle** sets the inset's coverage from 5° to 120°.
**Show Center Region** outlines or colors the inset.
**VR Overlay Quality** controls Vivecraft's high-resolution GUI option. Fixed
foveated rendering starts disabled and is suspended in ray-tracing guide debug views.

Wide reuses the inset's scene-linear lighting in the fully covered core while
tracing its own DLSS guides and blend margin. Set `foveation-reuse-inset = false`
under `[composite]` for a comparison with independent lighting in both layers.
Enable `[frame-stats]` in `config/caustica.toml` to record CPU timings to
`rt-frame-stats/frame.csv` and Vulkan timings per eye to `rt-frame-stats/gpu.csv`.
The GPU rows report the trace sizes and whether lighting reuse is enabled.

First-person arms and held models use the scene's LabPBR materials and light
transport on desktop and in both Vivecraft eyes. Desktop hand geometry uses
its own field of view and foreground visibility mask. VR hands follow their
controller poses in the scene. Specular reflections from VR hands and held items
can show the local avatar. The avatar's separate held-item geometry is filtered
out of those reflections to avoid duplicate weapons.
Enchantment glint adds Minecraft's animated texture to the traced item surface.
Menu hands and unsupported custom draws use the native hand pass.

## Status

Caustica is not a finished renderer yet. Current work focuses on visual
correctness, world coverage, stability, and making the SDR/HDR presentation
paths behave consistently.

## License

Caustica's project-owned source code and documentation are licensed under the
GNU Lesser General Public License v3.0 or later. See [LICENSE.md](LICENSE.md),
[COPYING](COPYING), and [COPYING.LESSER](COPYING.LESSER).

Release artifacts may bundle NVIDIA DLSS/NGX SDK components under NVIDIA's own
license terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## TODO List

- [ ] Nether/End sky, weather, volumetric fog/clouds
- [ ] NRD + FSR for non-NVIDIA GPUs
- [ ] LOD
- [ ] ReSTIR
