# Opacity micromaps

Caustica optionally enables `VK_EXT_opacity_micromap` and builds four-state opacity micromaps during asynchronous mesh preparation. Set the JVM property `-Dcaustica.rt.omm=false` before Minecraft device creation to test the any-hit fallback. The default is enabled on supported devices. This is a startup property, not a live setting.

## Content contract

`MeshBuild.Geometry.opacityMicromap` accepts immutable `OpacityMicromap` data for a cutout surface without a volume slot. Data follows geometry-local triangle order. Every triangle begins at a byte boundary and contains two-bit states in Vulkan bird-curve order, least significant bits first. Known transparent/opaque states must hold throughout the microtriangle for every placement and every use of the mesh; the producer accounts for filtering, animation, vertex alpha, instance data, and its coverage implementation. Unknown states preserve ordinary any-hit coverage evaluation.

The renderer does not inspect material shader data. Unsupported devices or subdivision levels omit the attachment. Unchanged geometry and micromap data can reuse a BLAS; changed meshes carrying micromaps rebuild instead of refitting. Build inputs and scratch survive GPU completion. Micromap storage is shared by the original and compacted BLAS and survives all retained readers.

The Vulkan build and BLAS attachment each specify their own explicit usage-count field. LWJGL's pointer setters do not infer this count. Size queries and recording use the same format, subdivision level, and triangle count. The micromap build-to-BLAS dependency uses micromap write/read access scopes. These contracts follow the [Vulkan EXT micromap specification](https://docs.vulkan.org/refpages/latest/refpages/source/VkMicromapBuildInfoEXT.html) and [BLAS attachment specification](https://docs.vulkan.org/refpages/latest/refpages/source/VkAccelerationStructureTrianglesOpacityMicromapEXT.html).

## Minecraft terrain

Resource compilation captures immutable mip-zero alpha minima and maxima across every declared animation frame. Terrain workers use those snapshots, without retaining or reading live native images. Each cutout geometry receives subdivision level 3 (64 microtriangles per triangle, 16 bytes per triangle). Classification covers the closed UV bounding rectangle, includes bilinear neighbors, and keeps atlas-edge or mixed regions unknown. All-unknown geometries omit their micromap.

The terrain uploader attaches the hint only for a mip-zero atlas view with anisotropy disabled. Stochastic surfaces, water boundaries, entities, and other producers without hints use their existing traversal paths. Resource reload produces a new material epoch and new mesh preparation.

## Validation: September 9, 2026

Affected module checks passed: 754 tests across API, engine, engine-vulkan, minecraft-content, minecraft-rendering, renderer-raytracing, and minecraft-client. Ray-tracing shader compilation, SPIR-V target checks, ABI verification, generated-layout reproducibility, and artifact checks also passed. New behavioral tests cover immutable input, invalid coverage combinations, bird-curve ordering, byte packing, bilinear footprints, animated alpha bounds, usage-count fields, and reuse/refit decisions.

Live testing used an RTX 5070 Ti, NVIDIA driver 616.56, and `VK_LAYER_KHRONOS_validation`. The copied world `caustica-omm-test-20260909` contains leaves, iron bars, glass, and water in front of a concrete background. Geometry edits, BLAS compaction, resource reload, and settled rendering ran without Vulkan validation errors after fixing an initial missing usage-count bug.

One edit recording contained 17 OMM builds and 101 BLAS compaction events. The OMM builds covered 2,108 triangles: 648 opaque, 63,820 transparent, and 70,444 unknown microtriangles. These are recorded build counts, including replacements, not simultaneous resident counts or timing measurements.

With jitter and water waves disabled, OMM-enabled and fallback captures used the same camera and waited for terrain preparation to drain. At 427×240 internal resolution, the full-frame depth and normal/roughness buffers were bit-identical. This remained true after resource reload. All compared buffers were finite. Diffuse-albedo BSDF estimates varied between captures, including repeated OMM-enabled captures; no bit-identical albedo or radiance claim is made. This test establishes no frame-rate improvement or 4K performance result.

The final attachment-storage revision also passed the ray-tracing checks and a fresh validation run with bit-identical depth and normal/roughness against the fallback. Attachments use heap storage so geometry count does not consume LWJGL's fixed-size stack. Temporary renderer settings were restored, world teardown drained terrain work, and the test clients exited successfully.

Local evidence is kept out of Git: `tmp/omm-final-check.log`, `tmp/omm-attachment-check.log`, `tmp/omm-heap-final.log`, `tmp/omm-fallback.log`, `tmp/omm-build-events.json`, `tmp/omm-enabled-final-reloaded.json`, and `tmp/omm-heap-comparison.json`. Capture manifests name the raw EXRs. For additional build statistics, enable the JFR event `OpacityMicromapBuild` through `jfr.start`; it reports recorded triangle/state counts and micromap storage bytes.
