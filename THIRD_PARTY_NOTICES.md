# Third-Party Notices

## Alpha-Piscium cloud renderer

The `clouds.slang`, `cloud_density.slang`, and `cloud_noise.slang` modules and
`src/main/resources/caustica/clouds` assets derive from
[Alpha-Piscium by Luna5ama and contributors](https://github.com/Luna5ama/Alpha-Piscium),
licensed under GPL-3.0. These portions retain that license; the original
Caustica source remains under LGPL-3.0-or-later. The combined cloud-enabled
shader is distributed under GPL-3.0. Corresponding source is published in
[this fork](https://github.com/sheno4/Caustica).

The cloud multiple-scattering model credits **HanPi Volume Cloud / AshenOneArt**:
<https://github.com/AshenOneArt/HPVolumeCloud>. Its MIT license and attribution
requirement are included in `licenses/alpha-piscium/HanPi-Volume-Cloud.txt`.
Alpha-Piscium additionally credits Inigo Quilez's smooth Voronoi, gradient noise,
and hash work, and Jarzynski/Olano's GPU hash functions. Its supplied license
texts are retained in `licenses/alpha-piscium` and packaged under `META-INF`.

See [the cloud integration guide](docs/alpha-piscium-clouds.md) for scope and
renderer-specific adaptations.

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA DLSS / NGX SDK

Caustica can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Caustica's `ngxshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.
