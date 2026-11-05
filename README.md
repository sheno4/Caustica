# AC8 Cloudly Runtime

A separately linked LGPL-3.0-or-later Java 21 library derived from Caustica's
native Cloudly implementation. It executes the original density, atmosphere,
cloud tracing, reconstruction and foreground composition recipes. The private
scene bundle supplies 81 programs and authored resources for Hangar, Mission 3
and Mission 11. Private game resources are not part of this source repository.

The host supplies its OpenGL device UUID and imports three dedicated Vulkan
allocations: RGBA16F colour input, R32F reversed projected depth and RGBA16F
output. Binary semaphores and explicit external queue-family barriers transfer
ownership without a CPU image copy. Java resource claims retire after the exact
Vulkan submission fence. Preparation runs on a separate CPU worker and uses the
same serialized compute queue.

RTX Remix exposes its final display image, rather than its original HDR scene.
The adapter lifts the display-linear input with inverse Reinhard, runs the
native compositor and applies Reinhard to the result. Its fixed display
exposure is normalized to the authored scene's sun illuminance. This is a
display adapter, not access to Remix's internal exposure, world lighting or
reflections. Those inputs require a runtime extension. Unchanged display values
below one round-trip through this bridge. Appearance and performance require
in-game verification.

## Build

Use JDK 21 and `gradlew.bat jar`. Unmodified bridge sources use the checked-in
bridge programs, verified against source and binary SHA-256 receipts. To change
the bridge shaders, set `SLANG_SDK` to Slang 2026.14.1 and `VULKAN_SDK` to a
Vulkan SDK providing `Bin/spirv-val.exe`; the build then compiles and validates
the bridges for SPIR-V 1.5 / Vulkan 1.2. These two small library-owned programs
are separate from the 81 private game programs.

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
