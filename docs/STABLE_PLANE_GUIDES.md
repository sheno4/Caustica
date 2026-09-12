# Stable-plane ownership and glass guides

The renderer represents at most three stable endpoints per pixel. Each endpoint owns a disjoint set of transport paths. A guide is a description of its endpoint, not a promise that every contribution in its signal has independent geometry guidance.

## Mixed surfaces

A surface with continuous transport retains an endpoint and delegates at most one delta lobe to another plane. The delegated lobe is whichever has greater incoming-throughput-weighted ACEScg luminance; ties favor transmission. This lets transmission continue through a second mixed glass face while also selecting reflection at grazing angles when reflection carries more energy.

The restart flags identify delegated reflection and transmission independently. The retained endpoint owns continuous transport and every undelegated delta lobe. When no slot remains, it owns all its lobes. Pure delta surfaces can reuse their current slot and queue another branch when capacity permits.

The parent removes each delegated delta lobe from both its sampling mixture and its specular BSDF estimate. A child receives the original incoming throughput multiplied by that delta lobe's RGB weight. Neither side rescales a physical lobe merely to allocate a plane.

For original mixture probabilities `pC`, `pR`, and `pT`, delegating transmission leaves mass `q = pC + pR`. The parent samples probabilities `pC/q` and `pR/q`; its physical RGB lobe values stay unchanged. The residual sampler's continuous PDF, including its new mixture probability, is also used by direct-light MIS and the next segment's emitter MIS. Multiplying that PDF by `q` again would be incorrect: the parent runs a separate sample of the entire residual, rather than being selected with probability `q`.

Build consumes emission on its explored prefixes. Fill skips emission at each restart endpoint and samples emission on undelegated continuations. Incoming segment absorption is applied once during each pass's evaluation; the stored restart throughput is before that segment's attenuation.

## Dominance

An endpoint's dominance weight is incoming throughput luminance times its pre-normalization residual probability mass. This probability mass is an importance estimate, not a radiance multiplier. Terminal objects retain incoming throughput as their dominance weight; a dark background therefore does not lose its geometry merely because its material albedo is dark.

A mixed two-face slab can use plane 0 for the front residual, plane 1 for the transmitted exit residual, and plane 2 for the background. The front residual may legitimately dominate if it carries more importance than the twice-attenuated background. In that case RR receives front-face depth and normals while its summed BSDF guides still contain the background contribution. NRD uses each plane's endpoint separately. Residual reflections retain their parent guide rather than receiving a separate reflected-object guide.

At reflection-dominant mixed surfaces, the selected child follows reflection. With an opaque reflected target, two planes suffice: the front residual and the reflected object. The transmitted contribution remains sampled by the front residual, but it has no independent background guide in that allocation.

## Validation expectations

Use fixed lighting, camera, settings, and upstream raw buffers. Do not use reconstructed color alone to establish endpoint placement or conservation.

- Clear glass interiors through a one-block slab should expose the background normal/roughness and depth after two crossings. Existing ray-tracing test-place captures show background view Z about `10.496`, versus `10.5` without glass; the difference comes from ray-origin offsets.
- Partial-alpha white stained glass should add the colored background to the summed BSDF guides when transmission is selected through both faces. A dominant front-face depth can be legitimate. A white-only summed guide with every available endpoint stopping on glass indicates that the background is still unrepresented.
- At a grazing angle with an opaque target in the reflected direction, the selected child should carry the reflected target's virtual depth, transformed normal, and roughness when reflection dominates.
- Compare averaged unexposed trace radiance under matched conditions to check that delegation does not lose or duplicate contributions. Changing the mixture changes sampling noise, so individual noisy frames need not match.
- Metadata encodes `(availablePlanes / 3, dominantPlane / 2, dominantDeltaDepth / 9, anyTransmissionOnDominantPath)`. It describes the dominant plane, not every endpoint. The RR depth and normal exports likewise describe only that dominant plane; NRD raw exports identify the final plane 0 inputs.

## Live guide validation — 2026-09-12

The copied ray-tracing test-place fixture passes the intended guide cases with SPBR and DLSS RR. The [baseline metrics](../tmp/gpu-investigation/glass-guide-metrics.txt) and [residual-ownership metrics](../tmp/gpu-investigation/glass-residual-metrics.txt) summarize raw guide bundles linked by the captures below.

| View | Measured result | Evidence |
| --- | --- | --- |
| Clear slab | Median view Z stays `10.496`, against `10.5` without glass. Target roughness remains about `0.8076`; metadata shows three planes, dominant plane 0, two delta crossings, and transmission. | [Capture](../tmp/gpu-investigation/glass-residual-clear.json), [guide image](../tmp/gpu-investigation/glass-residual-clear-guides.png) |
| Partial-alpha white slab | Median diffuse guide changes from neutral `(0.61719, 0.61719, 0.61719)` to `(0.62988, 0.62744, 0.69531)`, exposing the blue background contribution. The front residual legitimately dominates: view Z `5.5002`, three planes, dominant plane 0, delta depth 0. | [Baseline](../tmp/gpu-investigation/glass-white.json), [capture](../tmp/gpu-investigation/glass-residual-white.json), [guide image](../tmp/gpu-investigation/glass-residual-white-guides.png) |
| Clear reflection | Center view Z `10.6532`; reflected-target normal approximately `(-0.99951, -0.00392, -0.00389)` and roughness `0.7998`. | [Capture](../tmp/gpu-investigation/glass-residual-reflection.json), [guide image](../tmp/gpu-investigation/glass-residual-reflection-guides.png) |
| Partial-alpha white reflection | Center view Z `10.6520`, the same reflected-target normal and roughness `0.7998`; metadata shows two planes, dominant plane 1, one delta crossing, and no transmission on the dominant path. | [Capture](../tmp/gpu-investigation/glass-residual-white-reflection.json), [guide image](../tmp/gpu-investigation/glass-residual-white-reflection-guides.png) |

Shader compilation and renderer checks pass. These live captures validate the bounded guide behaviors; the averaged transport controls below separately assess radiance.

The dedicated shadow-payload candidate repeats these guide behaviors. [Raw measurements](../tmp/gpu-investigation/glass-shadow-metrics.txt) show clear-glass median background depth `10.496`, white-glass diffuse guide `(0.62988, 0.62744, 0.69531)`, and reflected-target center depths `10.6572` (clear) and `10.6562` (white), with target normal about `(-0.99951, -0.00392, -0.00389)` and roughness `0.7998`. Subpixel jitter affects individual samples. The subsequent GPU crash is documented separately; these successful captures do not imply runtime stability.

### Averaged transport control

A temporary immutable control disables build's delta delegation, leaving all transport lobes at the first endpoint; fill, lighting, layouts, and four-bounce limit stay identical. Both variants used serial graphics submission, the same copied fixture, noon lighting, manual exposure compensation −15 EV, and DLSS RR. Raw `trace-color` RGB was divided by its captured pre-exposure. Saturated or non-finite ROI samples were rejected. An earlier capture with +15 EV saturated and is excluded.

The [split capture](../tmp/gpu-investigation/radiance-shadow-split-evminus15.json) and [unsplit control](../tmp/gpu-investigation/radiance-shadow-unsplit-evminus15.json) average 64 frames per view over the same central 32×32 pixels. Channel-wise differences were **1.44–2.19%** for clear transmission, **0.66–1.03%** for white transmission, and **0.31–0.51%** for white reflection. Every difference was below 1.70 combined estimated standard errors. This bounded comparison found no statistically resolved radiance discrepancy; frame correlation, adaptive lighting proposals, and sampling variance limit precision. It does not establish exact conservation for arbitrary materials or scenes.

### GPU optimization controls

Certified opaque shadow blockers repeat the glass guide cases and keep the three glass raw-radiance comparisons within 0.60% per channel and 0.92 combined estimated standard errors. [Comparison](../tmp/gpu-investigation/radiance-blocker-comparison.json). Water and cutout leaves use the evaluated shadow path: their 64-frame controls differ by at most 0.81% and 0.76%, respectively, each below 0.65 combined estimated standard errors. [Evaluated baseline](../tmp/gpu-investigation/radiance-volume-shadow-baseline.json), [blocker candidate](../tmp/gpu-investigation/radiance-volume-blocker.json).

The compact radiance-intersection candidate also preserves the guide cases: transmitted clear-glass depth 10.496, white-glass guide albedo `(0.62988, 0.62744, 0.69531)`, and reflected-target center depths near 10.65. [Guide metrics](../tmp/compact-payload/glass-metrics.txt). Across clear glass, white glass, white reflection, water and cutout leaves, its raw-radiance channel means remain within 1.29% of the blocker baseline and below 1.83 combined estimated standard errors. [Comparison](../tmp/compact-payload/comparison.json). These are finite fixture controls, not a claim of universal equivalence or GPU crash freedom.

Direct SER hit-object extraction repeats all four glass guide views and the five raw-radiance controls. Channel means stay within 0.99% of the compact-intersection reference and below 1.46 combined estimated standard errors. [Guide metrics](../tmp/ser-direct-compact/glass-metrics.txt), [radiance comparison](../tmp/ser-direct-compact/optical-comparison.json). These checks cover the accepted-hit distance, triangle attributes and material reconstruction used after reordering.

Inline shadow ray queries retain transmitted clear-glass depth 10.496, white-glass guide albedo `(0.62988, 0.62744, 0.69531)`, and reflected-target center depths 10.657/10.652. [Guide metrics](../tmp/shadow-query-prototype/glass-metrics.txt). All five 64-frame raw-radiance controls remain within 2.30% and below 1.66 combined estimated standard errors of the direct-SER reference. [Comparison](../tmp/shadow-query-prototype/optical-comparison.json). The volume control has the largest uncertainty; these finite controls do not establish universal equivalence.

Queuing future exploration branches in existing per-plane records preserves these four guide cases. [Guide metrics](../tmp/branch-record-queue/glass-metrics.txt). White glass, white reflection and cutout controls remain within 1.13 combined estimated standard errors at 64 samples. Fresh 256-sample clear-glass and water controls differ by at most 0.28% and 0.76%, within 1.19 combined estimated standard errors. [Higher-sample comparison](../tmp/branch-record-queue/optical-comparison-clean256.json). The intermediate repeat with spilled water is excluded: fluid spread during an unfrozen startup added foreground refraction. The copied fixture was cleared of that spill, and water-ending captures now restore a dry slab before shutdown.

The MAXIMAL compiler setting also preserves all four guide cases: clear-glass center depth 10.496 and reflected side-wall center depth about 10.657 with its expected normal. [Guide metrics](../tmp/radiance-pipeline-prototype/slang-maximal/glass-metrics.txt). Clear/water raw radiance agrees with the clean queue reference within 0.77% and 0.91 combined estimated standard errors; white and white reflection agree within 0.37% and 0.60 errors. A cutout repeat at 256 samples resolves the initial approximately 2% difference: channel means agree within 0.21% and 0.21 errors. [Transmission comparison](../tmp/radiance-pipeline-prototype/slang-maximal/optical-comparison-clean-reference.json), [five-case controls](../tmp/radiance-pipeline-prototype/slang-maximal/optical-comparison.json), [cutout repeat](../tmp/radiance-pipeline-prototype/slang-maximal/optical-comparison-cutout256.json). Standard shader debug information and the floating-point mode are preserved; these finite image checks do not claim bitwise compiler equivalence.

### Isolated scalar SoA layout — 2026-09-13

The scalar SoA candidate and its matched AoS control each completed 28 bundles across RR, REBLUR + SR and RELAX + SR. All 728 EXRs pass meaningful-channel finite, header, extent, encoding and saved-hash checks. The parent inspected all six contact sheets: transmitted backgrounds and reflected objects remain visible, without an observed layout corruption. Clear-transmission center depth is 10.496 in both layouts; RR reflection retains the side-wall normal and virtual depth around 10.65. [Full matched review](../tmp/scratch-soa/optics/numeric-review/MATCHED_REVIEW.md).

Both copies originate from the same 942-file fixture snapshot. Camera, settings and window dimensions match; saved daylight and weather are identical and paused. Game time differs by 40 ticks, and RNG/jitter and accumulated history are not matched. The two settled captures per case are insufficient to establish radiance equivalence: noisy RR clear-transmission ROI means differ by up to 10.33%, while reconstructed means differ by about 0.18% in that case. The largest reconstructed difference across cases is 0.691%; neither value is an acceptance threshold. The longer control below follows up this noisy observation; the two-frame matrix alone does not establish radiance equivalence.

All recorded frames in the four short NRD recordings include all nine per-plane prepare/evaluate/merge stages plus SR, with positive durations. These recordings follow each route's final case; they do not expose every intermediate plane for every image. NRD raw guide buffers still represent plane 0, which can be invalid while the separate dominant reflected guide remains valid. Both layouts restore saved settings and pose. This bounded matrix found no storage or encoding defect and makes no GPU stability claim.

The subsequent 256-capture series per layout did not reproduce the earlier positive brightening: unexposed RGB means differ by −0.912/−0.931/−0.664% (SoA minus AoS), with similar within-run block variation and upward half-to-half drift. All 512 additional EXRs pass finite, saturation, frame/header and retained-hash checks. Camera/settings match; saved clock/weather match despite different gameTime. The four fixed quadrants also do not retain the earlier brightening. [Full comparison and limits](../tmp/scratch-soa-radiance/comparison256/COMPARISON.md).

Together with the matched guides, NRD contracts, visual inspection and compiled layout checks, this resolves the specific SoA optical concern. The six source files are promoted and the normal renderer build passes 217 tests. No exact-zero bias, universal optical equivalence, GPU crash fix or 60 FPS claim follows from this bounded acceptance.
