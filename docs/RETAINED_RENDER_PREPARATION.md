# Retained render preparation

This document describes the retained preparation contracts, their current implementation, and historical validation evidence. The recorded JFR measurements and test totals belong to the captured build identified below; they do not establish performance or validation status for the current worktree. Behavioral tests alone do not establish performance. See [REFACTOR_VALIDATION.md](REFACTOR_VALIDATION.md) for subsequent refactor checks and outstanding live scenarios.

The contract follows [VISION.md](VISION.md): workers prepare retained scene deltas and usable resources, publish completed revisions atomically, and let rendering retain the latest completed revision without joining its preparation. The performance target is no more than 10 ms of mod-attributed render-thread p99 in New World (2), at default resolution, during continuous forward+sprint flight including maximum spectator speed. This gate concerns the mod scope union, not the complete host frame or maximum individual stall.

## Persistent source roots

[SceneDirectory](../packages/engine/src/main/java/dev/comfyfluffy/caustica/engine/scene/SceneDirectory.java) commits scenes, environments, instances, meshes, and lights as one raw snapshot after an accepted edit group. [SnapshotPages](../packages/engine/src/main/java/dev/comfyfluffy/caustica/engine/scene/SnapshotPages.java) freezes dirty 128-entry buckets and path-copies an immutable binary radix directory. Unchanged branches, page values, and resource claims remain shared. A snapshot owns those page roots; their flat page iteration directory is materialized lazily once for a root.

`SceneDirectory.capture()` retains an already published root under a separate short publication mutex. It does not acquire the mutable editor or program locks, enumerate pages, or resolve shader identities. Publication displaces the old root under that mutex and releases it outside the mutex. Producer edit calls remain protected by the scene editor lock, and dirty-page freezing is part of the edit commit.

Raw [RetainedSceneSnapshot](../packages/engine/src/main/java/dev/comfyfluffy/caustica/engine/scene/RetainedSceneSnapshot.java) meshes contain immutable `MeshBuild` values with producer shader identities. They contain no resolved program indices. The renderer resolves identities using the immutable [ProgramComposition](../packages/engine/src/main/java/dev/comfyfluffy/caustica/engine/program/ProgramComposition.java) of its captured compiled program. Program replacement or removal can therefore change renderer resolution while preserving the same raw source root and ready meshes. Missing or foreign identities resolve to the composition's fallback.

## Minecraft entity publication

[RtEntityCapture](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/entity/RtEntityCapture.java) accumulates geometry in reusable host buffers. [MinecraftEntityMesh.copyOfRanges](../packages/minecraft-rendering/src/main/java/dev/comfyfluffy/caustica/minecraft/rendering/entity/MinecraftEntityMesh.java) copies each populated position, index, UV, and color prefix once, using the logical vertex and index counts. The immutable mesh owns its triangle list and stream copies, so subsequent capture reset or buffer reuse cannot mutate queued geometry. The full-array constructor and stream getters preserve defensive ownership.

[MinecraftEntityGeometry](../packages/minecraft-rendering/src/main/java/dev/comfyfluffy/caustica/minecraft/rendering/entity/MinecraftEntityGeometry.java) captures host-dependent inputs on its caller and submits immutable put, transform, and drop deltas. The serial entity publication worker owns the resident map, instance identity creation, accepted placement edits, ready-mesh replacement, and retirement. Applying an edit stages only touched residents; it does not copy the entire resident map. One channel edit commits the selected ready placement changes together, including the source directory's dirty-page freezing.

Unstarted groups merge into a pending map by entity key under a short mailbox lock. The newest put and transform supersede older pending values. A drop remains an identity boundary: a following put creates a new instance even when both arrive before the next drain. The worker detaches the pending map before applying it, so producers can accumulate the next deltas concurrently. Superseded captured uploads release their host texture claims off the render thread.

Each resident has at most one running mesh preparation and one newest queued capture. A newer deformation does not cancel usable in-progress completion; the worker publishes that completion with the resident's current placement, then starts the newest queued capture. Drop and lifecycle cancellation reject late completion and release its resources. GPU completion queues publication directly onto the serial entity worker; render does not scan completed jobs or wait for them. Successful publication invokes the captured acknowledgment callback.

[MinecraftVulkanEntityUploader.prepareUpload](../packages/minecraft-rendering/src/main/java/dev/comfyfluffy/caustica/minecraft/rendering/entity/MinecraftVulkanEntityUploader.java) resolves required host texture leases and material indices. The queued upload owns those leases. Its worker `finish()` allocates texture and sampler descriptors, writes them, allocates all four VMA buffers, packs the captured streams and shader records, and transfers ownership to the uploaded revision. Canceling an unstarted upload closes its captured leases without allocating GPU resources. Allocation and packing failures release the leases and partial native allocations exactly once. The job owner serializes `finish()` and `close()`.

Entity stop is a lifecycle barrier: it stops accepting work, retires residents on the publication worker, and joins outstanding packing before executor disposal. Ordinary put, transform, drop, and frame capture do not use that barrier.

## Minecraft terrain coordination

[RtTerrain](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/terrain/RtTerrain.java) observes live loaded columns and captures palette/model inputs on render. The coordination worker owns [TerrainWindow](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/terrain/TerrainWindow.java) set differences, wanted/removing sections, dirty-neighbor grouping, completed request bookkeeping, and retained state counts. Window observations stay ordered so an unload followed by a reload cannot disappear through coalescing. Render queues snapshot invalidations and drains them before extraction; it does not take the terrain preparation or publication lock during ordinary updates.

[TerrainDispatchPlanner](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/terrain/TerrainDispatchPlanner.java) receives coalesced candidate and column deltas. Its dedicated worker retains the eligible candidate set and sorts by visible replacement priority, horizontal distance, vertical distance, and section key. Camera changes rerank that worker-owned set. Eligibility requires the full 3×3 loaded-column neighborhood, including the observed radius-plus-one halo. Completed bounded plans are advisory: they do not reserve requests, and render can consume the newest available batch without waiting for the next plan.

[RtSectionSnapshots](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/terrain/RtSectionSnapshots.java) retains a bounded LRU of immutable palette copies. A cache hit requires both the section key and the exact live column object. Cache entries hold that column weakly, while queued regions strongly retain only their immutable palette revisions. An unloaded column or a replacement at the same coordinates cannot reuse the previous column's palette, including an all-air marker. Column availability changes invalidate the full captured height range plus its vertical halo, even when the column lies only in the horizontal observation halo. Invalidation is queued before the planner sees the corresponding availability delta. Block edits invalidate the affected section range expanded by one block; render drains those invalidations before palette extraction. Each worker region lazily decodes only its center section and one-block border.

[TerrainUpdates](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/terrain/TerrainUpdates.java) gives each request an atomic extraction token. Render reserves a current pending token before palette capture and transitions it again afterward; dirty, removal, and epoch invalidation reject superseded requests. Immediate invalidation retries if the current token was replaced during the operation. Late CPU or GPU results must still match the request and epoch before the coordination worker accepts them. Overlapping visible-neighbor groups retain one coherent replacement boundary.

Ready publication prepares the edit on its separate worker, then atomically claims each group's publication state. If invalidation wins the claim race, the group cannot publish. If the claim wins, concurrent dirtiness is ordered after that accepted publication and its queued regrouping prepares the next replacement. A failed multi-group claim rolls back prior claims while preserving concurrent invalidation bits. This closes the final validation-to-publication race without making render wait for edit preparation or commit.

Publication scheduling first checks whether a publication task is already scheduled. Its readiness probe returns on the first available group without collecting a result list or constructing per-group stream pipelines. The publication worker collects ready groups only when it needs the actual edit batch; request validation and publication claims remain authoritative.

Terrain reset invalidates the epoch and waits for coordinated clearing only at lifecycle boundaries. Worker-pool shutdown detaches its executors under its monitor and joins them after releasing that monitor. Ordinary extraction, retained planning, coordination, publication, and GPU readiness use separate lanes and completion ownership.

## Vanilla terrain suspension

[VanillaTerrainSuspension](../packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/VanillaTerrainSuspension.java) gives RT ownership a one-time entry and exit transition. The first canceled vanilla world render releases the view area's buffers to cancel active compile and resort tasks, clears queued compiles, settles the occlusion graph, and clears visible section lists. Those lifecycle operations do not repeat during ordinary RT frames.

While RT owns world rendering, the canceled hook copies the extracted loaded-column and empty-section additions and removals into retained sets before Minecraft reuses its delta buffers. It also drains gizmos. View-area rotation, section-tracker rotation, and occlusion graph traversal remain suspended. When RT ownership ends, including a failure or known source fallback, the next extraction performs one full `allChanged` rebuild at the current camera before vanilla capture resumes. A narrowly scoped guard prevents this rebuild's invalidation callback from resetting RT or clearing its failure state. Changing worlds resets the suspension state.

## Completed revision publication

[RtSceneRequest](../packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtSceneRequest.java) owns captured program and raw source claims, a visibility acknowledgment cutoff, the requested scene origin, and the scene-unit scale. Request identity includes those inputs. The host currently requests preparation during frame capture and startup progress; source and program changes do not directly notify the worker.

[RtScenePublication](../packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtScenePublication.java) runs one serial producer. A new unstarted request may replace the pending request. A completed result still publishes when a newer request is pending: continuous edits must not prevent usable results from reaching readers. Serial completion cannot overwrite a newer completed result. A failed preparation leaves the previous completed revision available, while lifecycle generations prevent an invalidated world or session from publishing obsolete work.

The preparation worker owns CPU assembly, resolved geometry, current instance metadata, emitter linking, packed light data, static light powers and identity lookup data, TLAS input packing, SBT records, and replacement resource reservations. It submits the TLAS build through [RtGpuExecutor](../packages/engine-vulkan/src/main/java/dev/comfyfluffy/caustica/engine/vulkan/runtime/RtGpuExecutor.java) and waits on that worker for the direct GPU-completion callback before publication. The callback runs on the separate GPU compute executor and does not require a render-thread progress call.

[RtSceneRevision](../packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtSceneRevision.java) publishes the exact compiled program, prepared source maps, completed trace bundle, and acknowledgment cutoff together. Each scene's [PreparedWorldGeometry](../packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtRetainedSceneBackend.java) contains its origin, instance/geometry/light metadata, emitter links, pipeline-specific hit SBT, completed TLAS and descriptor, static lighting revision, and source ownership. All scenes in the captured raw edit group enter the outer completed revision together.

Render-side acquisition and `beginFrame` retain completed roots. They do not flatten scene pages, derive motion, repack scene tables, or join CPU or GPU scene preparation. Lifecycle clearing is an explicit barrier: the runtime invalidates publications and quiesces preparation before backend caches are cleared. That barrier executes outside the backend monitor; ordinary frame capture does not use it.

## Current and submitted ownership

Each renderer view keeps its actual last submitted revision in [RtSubmittedRevision](../packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtSubmittedRevision.java). Recording retains current completed revision C and the view's last submitted revision P through GPU completion. Successful submission advances the view's predecessor to C. Preparing or abandoning B cannot make B the predecessor when the next submitted frame renders C.

P is independent of C, so the same completed revision can be repeated, skipped, or rendered by different views. View history retains P after its original GPU execution finishes because a later frame may still read its position streams. An explicit history reset releases the view's claim; already accepted executions retain their independent claims.

Preparation reserves exclusively writable storage. [RtCompletionSlotPool](../packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/resource/RtCompletionSlotPool.java) returns a slot only after the owning prepared revision's final release. Source roots retain mesh, BLAS, shader data, and other dependencies. The compiled program is retained by the outer renderer revision. CPU table objects and packed bytes borrow addresses; their Java reachability does not replace those resource claims. Prepared scene and trace retirement is delivered to the scene retirement executor, keeping final prepared-resource cleanup off ordinary render acquisition.

## Instance table and motion ABI

[RetainedGeometryRecord](../packages/renderer-raytracing/src/main/resources/caustica/shaders/world/world_common.slang) holds current shader implementations, bindings, coverage state, primitive indexing, emitter indexing, and an `instanceIndex`. TLAS custom indices retain the existing geometry-base convention: shaders address geometry with `InstanceID() + GeometryIndex()`.

[RtInstanceTablePlan](../packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtInstanceTablePlan.java) produces an immutable open-addressed table. It does not assign persistent numeric instance slots. The capacity is a power of two at least twice the live entry count, or one for an empty table. Identity zero marks an empty entry. The complete lookup key is `(instance identity, placement ordinal)`; linear probing checks both values and stops at an empty entry.

The 112-byte reflected `RetainedInstanceRecord` has this layout:

| Byte offset | Field |
| --- | --- |
| 0, 8 | `u64` instance identity, placement ordinal |
| 16, 24 | `u64` mesh revision, topology token |
| 32, 40 | `u64` current position address, opaque instance data |
| 48, 52 | `u32` position stride, reserved |
| 56 | zero padding |
| 64, 80, 96 | current affine `float4` rows |

CPU and [world_minimal.slang](../packages/renderer-raytracing/src/main/resources/caustica/shaders/world/world_minimal.slang) use the same unsigned wrapping hash: start with `identity + 0x9e3779b97f4a7c15 * (ordinal + 1)`, apply the SplitMix64 finalizer with shifts 30/27/31 and multipliers `0xbf58476d1ce4e5b9` and `0x94d049bb133111eb`, then mask the low 32 bits by `capacity - 1`.

Mesh revision tokens represent exact `MeshBuild` reference identity, independently of ready-mesh identity and structural equality. A topology token represents the complete [vertexTopologyCompatible](../packages/engine/src/main/java/dev/comfyfluffy/caustica/engine/scene/RetainedSceneSnapshot.java) signature: vertex count, non-null index revision, geometry count, and the ordered first-index/index-count slices. A null index revision has topology token zero. One persistent builder owns the backend's token namespace; weak canonical interners avoid permanent retention, while immutable table records strongly retain canonical token claims during comparison.

At a hit, the shader reads C through the current geometry record and probes P for the exact instance key. A matching placement supplies its previous transform. Previous positions are used only for equal mesh revision tokens or equal nonzero topology tokens; otherwise the current positions supply zero deformation history. Incompatible topology still preserves matching placement motion. Position stride comes from the selected position stream. Opaque instance and binding words remain uninterpreted.

## Retained instance upload deltas

The table builder accepts the previous prepared table for the same scene. It reuses an `InstanceRecord` only when identity, ordinal, transform value, opaque instance data, and exact mesh reference remain unchanged. Identical slot arrays reuse the previous table itself. `sameSlotAssignments` compares every slot's full live key and empty state, independently of transforms or data values.

[RtPackedInstancePages](../packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtPackedInstancePages.java) serializes immutable pages of up to 128 records. Unchanged record references at the same positions and an equal origin reuse the previous page buffer. Changed pages use the reflected serializer; vacant slots are completely zeroed. Origin changes repack the affected table's pages. An empty table still has one serialized empty slot.

Completed upload slots remember the immutable page buffers that were copied into them. `TlasBuilder.copyPages` copies only pages whose identity or byte offset changed for that reusable destination. Live revisions keep their storage exclusively owned, so these delta writes never patch a published table.

Each trace slot advances an assignment epoch when the instance table's full key-to-slot assignment changes. A batch from an older epoch must check its pages, but the assignment change does not clear geometry, hit, or emitter caches. Each geometry page compares its exact source identity, geometry base, origin, emitter address, and actual current `instanceIndex` against the resident bytes. Only a mismatching page is rewritten. Hit records retain their independent source/base/pipeline checks, and emitter residency follows its own source, range, and light-index inputs. A transform-only edit with unchanged assignments can reuse the batch's geometry residency directly.

Geometry and emitter ranges, unchanged current instance records, trace plans, packed TLAS inputs, and packed light pages also retain their existing caches. These caches reduce repeated serialization and upload work; they do not make every changed revision constant-time to prepare.

## Origins and camera history

The selected completed trace revision supplies the frame's actual origin and scene-unit scale through `FrameSnapshot.withSceneCoordinates`. Its TLAS, current instance transforms, light positions, camera offset, and procedural domain anchor therefore use one coordinate system even when preparation lags behind the host's requested origin.

For current origin Oc and previous origin Op, the shader translates previous affine rows into current coordinates with `float3(Op - Oc)`, computed by subtracting CPU doubles before conversion. Camera delta remains current world camera position minus previous world camera position. It does not include origin movement.

Camera projections use rotation-only view matrices. [stable_planes.slang](../packages/renderer-raytracing/src/main/resources/caustica/shaders/world/stable_planes.slang) projects the previous position using:

```text
previousVirtualPosition - currentCameraOffset + worldCameraDelta
```

[RtFrameHistory](../packages/renderer-runtime/src/main/java/dev/comfyfluffy/caustica/renderer/runtime/RtFrameHistory.java) preserves continuity across origin changes. Scene, scale, extent, route, frame gaps, explicit resets, and camera cuts retain their existing invalidation rules. Previous procedural time follows the actual submitted frame within the supported time window, so repeating a geometry revision does not freeze animation.

`SurfaceInput` receives current and selected previous affine transforms, their inverse-transpose normal transforms, and compatible previous geometric normals. Material overrides of `previous_geometry_normal` remain supported. The shader API has no previous opaque binding-data or instance-data word.

## Static lights and view feedback

[RtNeeAtPlan](../packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtNeeAtPlan.java) prepares current light identities, physical powers, and a hash lookup on the worker. Environment-emitter metadata and static GPU buffers belong to the completed light revision. There is no CPU previous-to-current dense light remap on the render path.

GPU feedback remaps a previous dense light index through P's light identity into C's identity lookup. [RtNeeAtBackend](../packages/renderer-raytracing/src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/scene/RtNeeAtBackend.java) maintains view-specific adaptive feedback history and uses exclusively owned writable slots rather than waiting for a ping-pong target. Accepted frame execution retains its current and previous feedback owners. A successful submission callback advances the feedback predecessor; abandoned work does not.

Adaptive distribution baking, pixel feedback, depth history, dispatches, and required GPU barriers remain frame-specific rendering work. Static light preparation and TLAS readiness do not add a render-side preparation join.

## Remaining limits and verification

The implementation still has these explicit limits:

- Requests acquire the current program and raw source roots on the calling frame/startup thread. Those acquisitions are short ownership operations, but publication is not driven directly by retained edit notifications. Program and source are retained separately; shader resolution is always against the captured composition.
- Changed revisions can still scan source pages, build a complete instance slot directory, compare table assignments, flatten static light metadata, or rebuild a TLAS on workers. Packed-page reuse is not shared GPU page-address indirection, and a newly allocated destination must receive all its required bytes.
- Scene edit commits still freeze dirty buckets and replace directory paths on the edit caller. Minecraft entity and terrain publication callers are their serial workers; required Minecraft extraction, host texture capture, column observation, and command enqueueing remain measurable render work. Other producers retain their own edit-caller contract.
- Frame resources and temporal consumers have independent costs. Exposure samples the newest completed feedback from six completion-owned readback slots and skips optional capture when all slots are occupied; it does not await a readback. Sizing or feature changes can still require device-idle work. Removing retained scene joins does not remove those separate costs.
- CPU behavioral and shader ABI tests do not prove the resulting live image or the <=10 ms render-thread p99 target.

Behavioral checks cover repeated and skipped submissions, abandoned captures, independent view history, exact instance keys and collisions, topology changes and null revisions, stride selection, empty revisions, immutable old page bytes, one-page instance edits, independent geometry/hit/emitter residency, program replacement, persistent raw-root capture, cross-origin camera inputs, and resource ownership. Entity checks cover logical stream ranges and capture buffer reuse, deferred upload, canceled host capture, allocation failure cleanup, pending delta coalescing, removal identity boundaries, and worker publication. Terrain checks cover stale extraction tokens, neighbor grouping, ordered window transitions, halo invalidation, weak source-identity palette reuse, readiness probing, publication claims, invalidation, and claim rollback. Suspension checks cover one-time entry, copied residency deltas, and one-time rebuild on ownership loss. Shader reflection verifies the packed record layouts. JFR tick instrumentation includes active gameplay work before frame capture in the upcoming frame's inclusive `runtime.tick` interval, so nested terrain stages remain visible without double-counting the stage union.

Host `CpuStage` scopes include all of `MinecraftFrameAdapter.capture`, the section maintenance explicitly invoked by the cancelled vanilla render hook, UI overlay preparation and compositing, HDR/frame-generation preparation, generated-frame presentation, producer dirty edits, and texture retirement. The active profile ends at `Minecraft.runTick` tail after presentation and texture retirement. Loops that do not render leave producer observations attached to the upcoming frame. Nested intervals must be unioned rather than summed.

The active-gameplay mod elapsed-time union includes waits incurred by mod operations, including frame generation's additional image acquire and generated-frame present. Ordinary host presentation, Reflex pacing, vanilla drawing, and diagnostic capture/automation remain outside these scopes. Worker work is reported separately. The enclosing `Frame` duration and thread CPU counters also span unrelated host work between mod calls, so they are not a measure of mod-only cost. The JFR reports below describe emitted scope coverage and measured p99 values for their recorded workloads and compiled build.

## Historical performance evidence

The following results are preserved measurements, not a benchmark of the current worktree. The `retained-deltas-validation.json` manifest identifies base commit `167112b7363f9373a6bb70854e5e98a497d35220`; the recorded build also contains the changes saved in `retained-deltas-build.diff` and `retained-deltas-build-hashes.json`. The base commit alone does not identify that compiled build. Subsequent source changes include files in the recorded hash set, so these numbers must not be carried forward as current performance verification.

The `gpu-revisions-max` and `gpu-revisions-fast` recordings are evidence for a previous compiled build, before the entity publication, terrain coordination, and expanded hook coverage described here. At 854×480, their emitted-scope union p99 values were 11.896 ms and 3.839 ms respectively; Frame envelope p99 values were 17.995 ms and 7.660 ms. Those unions are lower bounds for that build's incomplete hook coverage. Max-speed completed trace revisions averaged 75.471 ms apart, with source publication-to-assembly ages around 118–149 ms across the measured geometry categories. These measurements demonstrate neither the current build's timing nor completion of the target. The ignored `tmp/cpu-optimization` manifests and evidence reports retain the exact recordings, workload, timing gaps, and allocation/wait findings.

The preceding `coordination-max` recording measured 5,627 frames at 644×461 output and 322×231 ray-reconstruction input. Its union of every emitted Render-thread `CpuStage` had p99 **11.852 ms**, above the 10 ms target; the Frame envelope p99 was 16.770 ms. This recording predates the current page residency, vanilla suspension, palette halo, readiness, and single-copy changes.

The historical `retained-deltas` build was measured at 644×461 output and 322×231 ray-reconstruction input. Each reported union includes every emitted Render-thread `CpuStage`, with nested timestamps merged and no stage-name filter. Its recorded results are:

| Run | Spectator flying speed | Frames | Mod scope union p99 | Frame envelope p99 | Recorded gate result |
| --- | ---: | ---: | ---: | ---: | --- |
| `retained-deltas-max` | 0.2 | 6,819 | 9.524 ms | 14.529 ms | Pass |
| `retained-deltas-fast` | 0.05 | 10,316 | 4.383 ms | 6.631 ms | Pass |
| `retained-deltas-max-repeat` | 0.2 | 7,292 | 8.031 ms | 13.972 ms | Pass |

According to the three retained evidence reports, across all 24,427 captured frames, every frame had stages, no stage referenced an orphan frame ID, and all selected stage intervals stayed inside their Frame envelopes. Inclusive runtime/terrain ticks, host capture and maintenance, UI, presentation, and retirement remain covered. `host.worldMaintenance` p99 was 0.257 ms in the first max pass, 0.099 ms at normal speed, and 0.219 ms in the repeated max pass. No gameplay Render park, monitor wait, GPU preparation wait, or allocation-stall event was recorded, subject to configured event thresholds.

Completed trace revisions averaged 38.379 and 44.544 ms apart in the two max passes, about 26.1 and 22.5 revisions per second; their p99 gaps were 104.763 and 98.425 ms. Normal-speed gaps averaged 12.237 ms with p99 52.760 ms. Source publication-to-assembly ages averaged 70–82 ms for entities, placements and terrain at maximum speed; at normal speed they averaged 23.287, 30.793 and 62.061 ms respectively. These are source freshness measurements, not first-pixel latency. Trace preparation allocated 14.577–16.891 MB per maximum-speed revision and 6.183 MB per normal-speed revision. Freshness and worker allocation remain limitations even when rendering can reuse completed revisions without waiting.

Residual stalls remain visible: the largest Frame envelope was 136.013 ms and the largest mod scope union was 84.074 ms. Those frames overlapped 129.077 ms and 79.509 ms JVM safepoint-entry events, respectively; the largest recorded GCPhasePause was 0.064 ms. The repeated max pass's largest Frame was 61.672 ms and overlapped a 54.374 ms safepoint-entry event. The normal pass's largest enclosing Frames were around 50 ms, with native samples in vanilla host submission waiting and small mod scopes. The only recorded Render park belonged to diagnostic recording shutdown and overlapped no captured Frame. No slow frame or safepoint interval was removed to obtain the passing p99 values.

The exact recording UUIDs are `916a5062-6a1f-4732-8901-be7ba11af8ee` (max), `ff8ece96-fac7-4434-ba6c-9e4295c7b00d` (normal), and `5fce021f-ff9a-4f75-acf6-a832c9e6d4d6` (max repeat). Their `retained-deltas-*` manifests, detailed scope counts, worker/tail reports, and evidence summaries are retained in ignored `tmp/cpu-optimization`. The historical validation manifest records that runtime source hashes matched its pre-launch capture throughout those runs. That comparison does not describe subsequent refactors or the current worktree.

Live RT off/on checks before and after the flights restored terrain at the same camera. The post-flight manifest is `retained-deltas-flight-lifecycle.json`; the viewed vanilla `4d15decc` and RT `17ccf51c` screenshots show the respective terrain paths. The `retained-deltas-visual.json` buffer bundle contains finite albedo and near-unit finite normals, with terrain visible in the endpoint screenshot. An integrated-server crash during an earlier teleport warmup occurred before any JFR recording; restarting unchanged completed all three flights and the lifecycle checks without recurrence. Flight inputs were released, normal flying speed restored, and the client exited cleanly.

The historical checks after that client exit reported 610 tests with zero failures or skips: engine 86, raytracing 177, runtime 62, presentation 20, Minecraft rendering 78, and Minecraft client 187. `retained-deltas-final-checks.log` and `retained-deltas-test-results.json` preserve those results; `retained-deltas-validation.json` links the provenance, test, and lifecycle manifests. That recorded build met the <=10 ms mod render-thread p99 target for those normal and maximum-speed workloads. Current-build timing, scope coverage, and freshness require new recordings; the historical full-frame and freshness limitations above remain relevant follow-up measurements.

Future changes to this path should repeat the same scope coverage, lifecycle, visual, and flight checks. Retained ownership, motion/topology compatibility, lighting feedback, and origin behavior remain correctness contracts regardless of a passing timing result.
