# Fully covered, refractive Minecraft glass

Glass blocks and panes use opaque **coverage**, OpenPBR transmission weight 1, and an interior dielectric volume. Opaque coverage means every triangle is intersected; the BSDF still transmits light. Base texture alpha neither removes surface samples nor scales transmission. Texture RGB supplies the base color and Minecraft dielectric transmission tint. Authored normal/roughness maps remain active. Glass IOR is 1.5; ice retains 1.309 and water retains 1.333.

Clear glass is classified by block semantics even when Minecraft's raster layer is cutout. Stained/tinted glass and translucent terrain use the same fully covered boundary route. Held/dropped baked glass also receives opaque coverage and a dielectric volume; entity geometry ranges separate surfaces with and without interiors.

The volume implementation reads the boundary primitive's material IOR. `VolumeInput.primitiveIndex` carries that geometry-local triangle identity from the surface query, allowing glass and ice to share a geometry bucket without sharing optical properties. Initial view-medium queries use index zero. Existing resource retention, volume transitions, Fresnel/refraction and stable-plane branching supply the rendering behavior.

## Live validation

The rebuilt client loaded a copy of New World (2), `caustica-glass-20260914`, with SPBR-21 and DLSS Ray Reconstruction. Original-world blocks were not edited. Captures at 1920 × 1080 output / 960 × 540 trace resolution include the original cave pose and an elevated one-block-thick glass wall in front of redstone, lapis, gold and quartz blocks. A same-pose glass/air comparison freezes world ticks and disables fog during the comparison; fog settings and ticking are restored afterward. Automatic exposure remains active, so brightness differences are not radiometric evidence.

`tmp/glass-20260914/manifest.json` retains the settings, poses, screenshots and same-frame raw trace radiance, reconstructed color, normal/roughness, physical/virtual depth and stable-plane metadata. `analysis.json` and the local `tmp/glass-inspect.py` retain the numerical inspection.

- All **172,520** pixels in the analytically projected interior of the glass front face hit that physical plane. Maximum reconstructed X error is **0.00000253 blocks**; none pass through a texture-alpha hole. The test excludes a 0.05-block strip around the wall perimeter.
- The glass fixture has **177,136** pixels whose dominant stable path crossed transmission; the air control has **zero**. The original cave capture has **118,125** such pixels. These are decoded from the documented stable-plane metadata alpha channel.
- Inspected glass/air images show the backing geometry displaced through the glass slab, and the glass metadata forms a continuous covered region. Normal-map detail and Fresnel reflection remain visible. These bounded captures do not claim exhaustive validation of every resource pack or nested medium configuration.

Minecraft client tests, Minecraft rendering checks, ray-tracing checks and shader compilation pass (`tmp/flight-spikes/glass-client.log`). Tests cover clear/stained/pane classification, glass IOR, volume registration, geometry binding ownership, coverage flags, and separation of entity volume ranges. The fog performance optimization from [the flight report](FLIGHT_PERFORMANCE_20260914.md) remains in place; its performance numbers precede the glass correction and are not presented as measurements of the changed glass workload.
