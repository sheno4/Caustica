# RTXPT architecture review

Reviewed 2026-09-05 against Caustica `0f1c02ec` and the local RTXPT checkout `f08d1c7` (README identifies v1.8.1). This document records the pre-rewrite source inspection, with one float32 arithmetic check. See [Trace stage](TRACE_STAGE.md) for the implemented architecture and runtime validation.

The useful architecture to adopt is **build stable planes -> reconstruct local light sampling -> fill stable planes -> prepare reconstruction inputs**. Caustica already has much of the resource scaffolding, but its tracing and plane-attribution contracts differ substantially. Preserve the Vulkan-native engine, retained scene revisions, producer-defined materials, ACEScg, and per-view history required by VISION.md.

## Pass architecture

RTXPT's realtime sequence, omitting optional RTXDI passes:

1. Prepare scene/environment lights and global NEE-AT distributions (`LightsBaker::UpdateBegin`).
2. `PathTracePrePass`: build stable planes by exploring deterministic branches, including primary surface replacement, and store endpoint/branch/throughput information.
3. `VBufferExport`: export depth, motion, and related inputs from those planes.
4. `LightsBaker::UpdateEnd`: reproject/filter feedback and build the local light samplers using current depth and motion.
5. `PathTrace`: fill stable planes with stochastic transport. The fill path reconstructs a starting path from the base plane; `FirstHitFromVBuffer` narrows the first ray interval around the known hit. It does not eliminate all retracing.
6. Bake denoising guides, including specular hit-distance processing.
7. Either prepare/evaluate/merge each active NRD plane, or prepare the combined DLSS-RR inputs and run RR. These are distinct reconstruction routes.

Evidence: [RTXPT pass orchestration](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Sample.cpp:2442), [fill initialization](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Shaders/PathTracerSample.hlsl:34), [NRD plane loop](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Sample.cpp:2579).

Caustica currently runs all NEE-AT baking before primary tracing. Primary tracing shades the camera hit, selects a stochastic continuation, then separately follows up to two deterministic guide chains. Indirect tracing resumes the camera continuation and produces more lighting. The guide chains are not the transport continuation entry points.

Evidence: [frame order](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameRenderer.java:509), [primary tracing and guide exploration](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/primary_rgen.slang:393).

**Recommended internal Trace sequence:**

```text
Prepare light identities and global distribution
  -> BuildStablePlanes
  -> Export depth/motion + reconstruct local NEE-AT
  -> FillStablePlanes (stochastic bounces remain inside the dispatch)
  -> Finalize reconstruction guides/signals
  -> NRD per active plane, or combined DLSS-RR
```

Use explicit Vulkan barriers at these producer/consumer boundaries. The plane-build pass should not run ordinary stochastic NEE; otherwise it consumes the sampler that needs its depth/motion outputs. Avoid turning each bounce into a separate dispatch. These are internal passes under the existing Trace responsibility, not a reason to add a general public render graph.

## Stable-plane contracts to port

**A plane must represent a transport branch endpoint, not just an auxiliary guide.** RTXPT stores branch identity, path throughput, ray state, depth, and material estimates, then tracks the stochastic path against that branch. Its build pass can enqueue further branches at later vertices, subject to a bounded plane budget. Caustica explores alternate branches only at the initial junction, then follows the chosen continuation at subsequent junctions.

Caustica's `StablePlaneRecord` stores guide positions, scalar branch weights and noisy signals, but no restart ray/medium state or RGB prefix throughput. Add the state actually needed to restart transport, or store a compact associated continuation. A scalar branch weight used for dominant-plane selection is not a substitute for RGB transport through colored glass or reflection.

**Match the complete branch.** `stablePlaneForEvent` selects a plane using only its first reflection/transmission event. The indirect path does not carry the full stable branch identity. A stochastic ray can therefore be attributed to a deterministic endpoint it did not reach. This is a reconstruction correctness issue even if the sum of raw radiance remains valid.

**Classify diffuse/specular at the plane endpoint.** `runRetainedIndirect` keeps the initial camera lobe as `firstLobe` for the whole path. For a glass or mirror prefix followed by a diffuse endpoint, that labels subsequent lighting specular. RTXPT sets `stablePlaneBaseScatterDiff` on scattering at a stable plane. Adopt endpoint-relative signal classification, distance origin, and demodulation together.

**Separate deterministic radiance from noisy radiance.** Caustica's guide-only closest hits return before emission accumulation; the stable-radiance output is populated from primary emission/sky. The deterministic reflected/refracted prefix is not fully used as a stable-radiance transport result. A real build/fill contract should account for prefix radiance and throughput once and ensure fill does not add the same emission twice.

**Retain the existing reconstruction scaffolding.** Caustica already runs three separate NRD backend passes with prepare/merge. The main missing work is producing the correctly attributed plane signals. After that, an active-plane budget of 1/2/3 can trade cost against decomposition quality; reducing the plane budget must fold remaining transport into a valid base plane rather than discard energy. DLSS-RR should continue to receive its combined guides, rather than mechanically adopting the NRD loop.

Evidence: [current plane records](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/stable_plane_types.slang:12), [first-event matching](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/stable_planes.slang:79), [current signal classification](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/retained_indirect.slang:39), [existing NRD loop](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtReconstruction.java:187), [RTXPT branch matching and classification](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Shaders/PathTracer/PathTracerStablePlanes.hlsli:327).

## NEE and transport correctness findings

### 1. Terminal bounce drops the competing BSDF event

At `bounce == frame.maxBounces`, the closest-hit shader still computes NEE with light/BSDF MIS, but the indirect loop breaks without tracing the selected continuation. For a BSDF-sampleable linked emitter, NEE is downweighted and the complementary emission contribution is absent. This loses energy at that boundary.

Port RTXPT's terminate-at-next-hit contract: consume the final scatter ray's emission/miss, then stop before another NEE/scatter. Alternatively explicitly disable the competing BSDF technique in terminal-vertex NEE and define that depth convention consistently. The first option follows RTXPT more closely.

Evidence: [terminal break](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/retained_indirect.slang:111), [forward MIS](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/closest_hit.slang:47), [RTXPT stop ordering](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Shaders/PathTracer/PathTracer.hlsli:676).

### 2. Thick-volume direct lighting and continuation use different scattering models

`runClosestHit` sets `geometry_thin_walled`, but `surfaceClosure` does not use that flag: transmission is evaluated as a thin sheet. Meanwhile `retainedSampleContinuation` selects a delta volume-boundary event, or samples a surface with transmission removed; it also scales the reverse density by the non-boundary selection probability. Direct lighting evaluates the original closure and its original lobe probabilities.

Thus direct-light evaluation and reverse MIS are not describing the same continuous lobes at a transmissive volume boundary. This is more fundamental than tuning the NEE-AT formula. Resolve a single active BSDF/medium contract for direct evaluation, sampling, reverse PDFs and deterministic branch extraction. Preserve discrete delta probabilities separately from solid-angle densities. The exact visual magnitude still needs a rendered test.

Evidence: [volume sampling](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/closest_hit.slang:259), [closure construction](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/surface_bsdf.slang:117).

### 3. Random conversion can return the excluded upper endpoint

`(float(state) + 0.5) / 2^32` can round to 1.0 in float32. This was reproduced for `state = 0xffffffff`. Global sampling clamps the index, but local CDF sampling returns no light for that value. Use a conversion with an explicit [0,1) contract, such as taking the high 24 bits and multiplying by 2^-24. This is a small correctness issue, not an explanation for large noise or performance differences.

Evidence: [random conversion](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/retained_lights.slang:37).

### What should not be called a bug

The current sampler uses deterministic global/local candidate counts and a count-weighted mixture proposal. Its reservoir normalization is consistent with that construction: conditional on the candidate set, selecting with weight `target/q` and multiplying the selected contribution by `sum(weights)/(M*target)` recovers the average candidate estimator. A local distribution with missing lights is allowed because global candidates retain full support.

RTXPT instead draws using source-specific densities and applies global/local MIS late. These constructions must be ported as complete estimators; replacing just one PDF or normalization factor would be incorrect.

Similarly, `M * q * shapePdf` is a heuristic outer-MIS density, not the exact selected-reservoir density. It is not automatically biased if forward/reverse weights are complementary for the same integrand and sampling state. Caustica deliberately samples one diffuse/specular component and uses component PDFs on both sides for ordinary surfaces; replacing only forward PDFs with the total BSDF PDF would break that agreement. The volume and terminal-bounce cases above violate the required contracts.

Counting one feedback winner per pixel globally is also consistent with RTXPT's P0 design. Do not replace that count with raw radiance sums merely because the reservoir stores a weight.

Evidence: [RTXPT candidate selection and late MIS](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli:90), [outer MIS pair](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli:282), [feedback counting](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Lighting/LightsBaker.hlsl:1186).

## NEE-AT quality and performance ports

| Priority | Current behavior | Proposed adoption |
|---|---|---|
| High | Local history requires a stationary camera and procedural surface animation disabled. | Reproject from current stable-plane depth/motion; reject disocclusions, remap light identities, merge neighboring reservoirs and fill holes. Bake local distributions between build and fill. |
| High | All indirect bounces use global-only sampling, but their feedback can enter the local tile distribution. | Track screen-space coherence through the path. RTXPT uses ray-cone width/path length and tags feedback; incoherent winners contribute globally but are removed from local reconstruction. |
| High | Full RGB BSDF and per-component MIS evaluated for every candidate. | Use a cheaper supported target for candidate selection, then evaluate full BSDF and final MIS only for the visible winner. RTXPT uses max light contribution times BSDF PDF as its inexpensive target. Preserve estimator normalization and target support. |
| Medium | Local draw binary-searches a CDF; proposal evaluation probes a hash table, including for each candidate. | Benchmark RTXPT's packed local proxy list: constant-time draw with multiplicity PDF, plus reverse lookup only when needed. Current 128 slots use 8 bytes each; RTXPT's packed entries use 4 bytes, subject to its index/count limits. |
| Medium | Each global proxy does a binary search for its owning light. | Copy bounded proxy-fill jobs with explicit light/range information. This removes the per-proxy search while distributing very bright lights across bounded work. |
| Medium | Each pixel atomically increments a global feedback counter, including the common invalid bucket. | Aggregate equal IDs within a subgroup before the atomic, using supported Vulkan subgroup facilities. RTXPT's optimized implementation is under its D3D12 path; do not assume the HLSL intrinsics are a drop-in Vulkan port. |
| Medium | Every frame rebuilds the CPU identity map and powers; capacity growth reallocates both frames and waits. | Reuse unchanged immutable light-revision plans; grow/retire GPU storage by completion without waiting on the render thread. Profile before choosing capacity policy. |

History evidence: [stationary gate](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameHistory.java:33), [current local bake](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/nee_at_bake.slang:168), [RTXPT reprojection](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Lighting/LightsBaker.hlsl:1353), [RTXPT feedback tagging](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Lighting/LightsBaker.hlsl:1218).

Performance evidence: [current candidate shading](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/closest_hit.slang:33), [current proxy fill](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/nee_at_bake.slang:147), [RTXPT bounded jobs](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Lighting/LightsBaker.hlsl:1008), [current host preparation](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtNeeAtBackend.java:98).

## Transport performance and ownership

Keep stochastic path state live across bounces. Caustica invalidates/writes/reads a 48-byte continuation record in every indirect iteration, and repeatedly quantizes direction, throughput and medium state. RTXPT maintains a path loop and packs state across hit invocation. Use the continuation buffer at actual pass boundaries; benchmark compact payload versus hit reconstruction/register pressure before selecting the final layout.

Separate guide-build, radiance, and visibility state. `WorldPayload` includes radiance splits, previous guides, and two guide continuations; `runClosestHit` prepares guides even for ordinary indirect hits. Compile pass-specific behavior so stochastic bounces do not perform unused guide work. Source size alone does not establish actual register/spill cost; inspect compiled shaders and GPU captures.

Preserve SER for incoherent stochastic tracing; RTXPT explicitly disables sorting in its stable-plane build mode. Caustica already has a HitObject/ReorderThread path, so this is refinement, not an absent feature. Evaluate hit/material/termination coherence with measured occupancy and traversal cost instead of assuming additional sort bits improve performance.

NEE-AT pixel history is currently keyed by SceneId. Split scene light identity/resource ownership from view-dependent depth, motion, feedback and local distributions. Stable-plane and denoiser histories also belong to the rendered view. This follows VISION.md and prevents two views of one scene from sharing incompatible temporal data.

Evidence: [continuation loop](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/retained_indirect.slang:59), [packed representation](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/retained_path_queue.slang:74), [payload](C:/Users/i/Developer/mc/dlss-mod/packages/renderer-raytracing/src/main/resources/caustica/shaders/world/world_minimal.slang:10), [RTXPT trace/SER](C:/Users/i/Developer/mc/rtx-reference/RTXPT/Rtxpt/Shaders/PathTracerSample.hlsl:115).

## Implementation order and acceptance

1. Establish a raw accumulated reference path with controlled bounce semantics and radiance clamps disabled for comparisons. Fix terminal-event handling, volume BSDF agreement and RNG conversion. Check linked-emitter forward/reverse PDFs numerically.
2. Introduce an authoritative stable-branch/endpoint transport contract, shared BSDF delta-lobe extraction, RGB prefix throughput and endpoint-relative signals. Test plane-count changes without losing radiance; retain independent histories per view.
3. Split BuildStablePlanes and FillStablePlanes, placing local NEE-AT reconstruction between them. Add motion/disocclusion/coherence-aware feedback. Keep the global sampler as the full-support baseline.
4. Optimize candidate shading, local proxies, global fill jobs and feedback atomics individually. Then address payload/continuation traffic and host waits with measured evidence.

Use executable shader/numerical tests and rendered comparisons, not source-text assertions. Existing Java NEE-AT property tests cover distribution arithmetic and identity/history planning; they do not establish GPU shader equivalence, synchronization correctness or transport convergence.

Correctness scenes: one linked area emitter; mixed diffuse/glossy surface; thick glass versus thin sheet; mirror -> glass -> diffuse endpoint; moving emissive objects and removed/reordered lights; camera pan/disocclusion; two views of one scene; zero lights and all-dark lights. Compare means/confidence intervals across candidate counts and global/local modes before judging denoised images. Verify that the sum of plane signals plus stable radiance matches the raw transport result before reconstruction.

For performance, record GPU timestamps for light preparation, stable-plane build, local bake, fill, guide preparation and reconstruction. Track candidate evaluations, shadow rays, guide rays, average stochastic path length, active planes, local-history validity, shader registers/spills and CPU wait time. Compare both fixed-spp cost and time to a matched image error on the target 5070 Ti, 4K output/DLSS Performance configuration. No percentage speedup is established by this audit.

Upstream context: [official RTXPT repository](https://github.com/NVIDIA-RTX/RTXPT). The concrete comparisons above use the local checkout rather than assuming upstream HEAD matches it.
