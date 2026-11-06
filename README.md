# AC8 Cloudly Runtime

A separately linked LGPL-3.0-or-later Java 21 library derived from Caustica's
native Cloudly implementation. It executes the original density, atmosphere,
cloud tracing, reconstruction and foreground composition recipes. The private
scene bundle supplies the original programs and authored resources for Hangar, Mission 3
and Mission 11. Private game resources are not part of this source repository.

`BorrowedCloudlyRuntime` records into the host's Vulkan command buffer and
borrows its device. It never submits or destroys the host queue or device.
Preparation precedes path tracing, and camera composition follows reconstruction
but precedes exposure and bloom. Both sides use scene-linear HDR. Physical
first-hit depth remains separate from reconstruction depth. Exact GPU completion
leases retain every exported image, sampler, buffer and address.

The custom native runtime evaluates the original cloud and atmosphere along
actual reflection and indirect-ray segments. Original atmospheric transmittance
and directional cloud shadow maps attenuate distant lights at each surface.
Catalogue stars use the original Allocate, Fill and Render programs with GPU
bucket offsets. The primary view uses a camera render at the authored resolution
fraction; an infinite-distance catalogue cube supplies reflection emission.
Cloud and atmosphere transport is never replaced by that cube.

The legacy `CloudlyRuntime` retains the separate-device display adapter for
hosts without the recording extension. RTX Remix's complete AC8 integration
requires the borrowed-device path and all native capability bits. Compilation
and package checks do not establish rendered appearance or frame rate.

## Build

Use JDK 21 and `gradlew.bat jar`. Unmodified bridge sources use the checked-in
bridge programs, verified against source and binary SHA-256 receipts. To change
the bridge shaders, set `SLANG_SDK` to Slang 2026.14.1 and `VULKAN_SDK` to a
Vulkan SDK providing `Bin/spirv-val.exe`; the build then compiles and validates
the bridges for SPIR-V 1.5 / Vulkan 1.2. These two small library-owned programs
are separate from the private game programs. The library-owned GPU bucket scan
source is under `shaders/library/`; its prepared module accompanies the private
star pipeline metadata.

For a complete private delivery, add
`-PcloudlyBundleInputRoot=<prepared-private-bundle-directory>`. The bundle root
contains `manifest.json`, `native-runtime/`, `textures/` and `scenes/`. A source
build without that property compiles the public library and has no authored
game scenes.

The JAR embeds its corresponding source, license texts and build scripts under
`META-INF/cloudly-source/`. The host uses this as an independently replaceable
nested JAR; rebuilding it does not require modifying the Apache host sources.

The standalone device requires Windows, the same Vulkan physical device as
OpenGL, external Win32 memory/semaphores, buffer device addresses, arithmetic
and storage-image features, fixed 32-lane compute subgroups and
`VK_KHR_synchronization2`. Unsupported features produce an explicit error.

See [NOTICE.md](NOTICE.md), [LICENSE.md](LICENSE.md), [COPYING](COPYING) and
[COPYING.LESSER](COPYING.LESSER).
