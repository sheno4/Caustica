# Alpha-Piscium clouds

This fork ports Alpha-Piscium's cumulus density function, cirrus gradient-noise
layers, original noise textures, OPAC phase tables, and ordered eight-sample
isotropic multiple-scattering approximation to Slang.

Clouds are enabled in the Overworld. They participate in camera paths, reflected
and refracted paths, indirect sky lighting, and sun/moon shadow rays. Path segments
end at geometry, so clouds cannot overwrite nearer terrain. Rays inside water or
solid dielectrics do not integrate an additional air cloud medium.

## Configuration

The `[clouds]` section of `config/caustica.toml` accepts:

```toml
[clouds]
enabled = true
height-km = 1.0
thickness-km = 2.0
coverage = 0.5
density = 1.0
cirrus-density = 1.0
wind-km-per-second = 0.002
steps = 64
```

Caustica's atmosphere uses 100 blocks per kilometre above the world's sea level.
At sea level 63, the default cumulus layer therefore spans Y=163 through Y=363.
Cirrus occupies 9.0–9.2 km. Height supports 0.1–4 km, thickness 0.1–4 km,
coverage 0–1, and view steps 16–128. `density = 0` disables both cloud layers;
`cirrus-density = 0` disables only cirrus. Settings apply on restart, like other
file-based Caustica settings. Larger step counts increase GPU cost.

## Renderer adaptation

The density and texture sampling equations retain the source algorithms. Noise
tables use packed device-address buffers with periodic bilinear/trilinear
filtering; no new Vulkan image or sampler feature is required. Phase tables use
clamped half-float interpolation and AP0-to-BT.709 conversion before Caustica's
final ACEScg conversion.

Transport uses Caustica's atmospheric transmittance and sky-view LUTs. Cloud
layers are planar with a 200 km trace limit. They use a deterministic full-path
march and the existing DLSS output path, rather than Alpha-Piscium's separate
low-resolution cloud history, spherical shell geometry, ambient-cloud LUT,
blue-noise jitter, and epipolar atmosphere composition. OPAC phase tables are
used directly. These choices mean this is a renderer port, not a pixel-identical
reproduction of the shader pack.

For cloud-enabled paths, pass A retains dielectric guides and lets pass B consume
the original camera segment, so cloud scattering before glass/water is integrated
once. Pass B uses its existing stochastic dielectric continuation. Disabling
clouds retains the original split-continuation path.

This adds work to indirect rays and directional shadow rays. In-game image
quality, frame time, DLSS history behavior, and Vulkan device execution must be
assessed separately from compilation. No screenshot or image-recognition tests
are part of this port's verification.

## Sources and attribution

Source snapshot: user-supplied `Alpha-Piscium-main.zip`, SHA-256
`ba54b8f1c48327d4babca1707efb90b7710d38d4f163799d890164bc03004ef8`.
Caustica base: `330acd2d743bb6b2e27b53a4adb4a3c852b9e141`; the supplied
Caustica ZIP matches that revision after line-ending normalization.

- [Alpha-Piscium / Luna5ama](https://github.com/Luna5ama/Alpha-Piscium):
  `Cumulus.glsl`, `Cirrus.glsl`, `Mediums.glsl`, `RenderVolumetric.comp.glsl`,
  `Hash.glsl`, `GradientNoise.glsl`, `ColorSpaces.glsl`, and cloud textures.
- [HanPi Volume Cloud / AshenOneArt](https://github.com/AshenOneArt/HPVolumeCloud):
  isotropic multiple-scattering derivation used by Alpha-Piscium.
- Inigo Quilez: smooth Voronoi and gradient noise; Mark Jarzynski and Marc Olano:
  GPU hash functions, as credited by Alpha-Piscium.

The imported cloud modules/resources are GPL-3.0. License texts and notices are
included in the source and in the JAR's `META-INF/licenses/alpha-piscium` folder.
