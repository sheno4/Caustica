# Examples

These scenarios make the observable behavior in VISION.md concrete. API names, callback arrangements, and implementation sketches are illustrative, not compatibility constraints. Alternatives are welcome when they preserve the required behavior.

## Neighboring Minecraft sections change together

**Required behavior:** a frame sees either all old section revisions or all replacements. It never sees a mixture that creates a temporary hole at their shared boundary.

1. A world edit requires sections A and B to be remeshed. Minecraft identifies them as one publication group and prepares their replacements independently.
2. A finishes first. Both old sections remain in the scene while B is still being prepared. Frames continue rendering, and unrelated section updates can publish.
3. B finishes. Both replacement meshes and any corresponding primitive-light changes are ready.
4. Minecraft applies one atomic edit group replacing the affected instances and lights.
5. The next snapshot sees the whole replacement group. Already captured frames continue using the old group.
6. Old resources are released after their final scene, frame, job, history, or producer reference ends.

If a newer world edit supersedes the pending group, the producer discards or rebuilds the affected preparation as a coherent group. A late completion must not publish half of an obsolete update.

## An extension animates a mesh with GPU compute

**Required behavior:** the current mesh stays visible until a complete replacement is ready. Its geometry, shader data, and BLAS always describe the same revision.

1. An instance uses mesh A. The extension records compute work generating new positions and opaque shader data, such as vertex colors, into separate resources.
2. The job retains its dependencies through execution. On completion, the extension requests mesh preparation from the generated geometry inputs.
3. The engine builds mesh B. With compatible topology, it may perform an out-of-place refit using A's available BLAS. The build retains A's source resources for as long as needed.
4. While B is being prepared, snapshots continue selecting A. Independent transform edits can move the instance using A.
5. Once B is ready, the extension atomically replaces the instance's mesh and associated data references, preserving its current transform. There is no intermediate removal.
6. New snapshots use B. Older frames, refit jobs, or temporal history may still retain A.
7. A and its dependencies are destroyed only when their last ownership claims end. Completing one frame does not necessarily cause final release.

Separate compute completion and build submission are an illustrative sequence. The implementation may combine dependent GPU work while preserving readiness, publication, and lifetime behavior.

## Opaque shader data owns private GPU resources

**Required behavior:** the shader can safely follow its opaque pointer for the entire GPU use, without the engine understanding the provider's storage layout.

1. A provider uploads a private material table containing descriptor-heap indices for textures.
2. It creates a shared owner for the table, textures, and descriptor allocations, supplying a final-release callback. The shader-data value is the table's GPU address.
3. Published material or instance data retains this owner. Captured frames keep the selected revision alive through GPU completion.
4. The provider creates a replacement table and owner, publishes the new revision, and releases its claim on the old owner.
5. Older frames can still read the old table and descriptors. New snapshots use the replacement.
6. Once all old references end, the callback runs once off the render thread. It frees the private allocations or releases their shared owners. Dependencies still used elsewhere remain alive.

The same ownership rule applies if the value points into a larger shared allocation. The provider defines the ownership granularity.

## Two scenes share a mesh and material

**Required behavior:** scene membership does not determine shared resource lifetime.

1. A producer prepares one mesh and material, then places instances referencing them in scenes A and B.
2. A frame captures both scenes. Each instance has its own transform and scene identity.
3. Scene A is removed. Future snapshots exclude A, while scene B remains renderable.
4. The already captured frame can finish rendering A. The mesh and material remain alive through B and any other outstanding references.
5. Removing B permits final destruction only after producers, jobs, frames, and history have also released their references.

This scenario requires multiple scenes and shared resources; it does not require ray traversal between scenes.

## A replacement finishes after removal or a newer update

**Required behavior:** late asynchronous completion never resurrects removed content or overwrites a newer selected update.

1. A producer starts preparing replacement B for an instance using A.
2. Before B finishes, the instance is removed, or a newer request C supersedes B.
3. The producer checks whether a completed result is still wanted before applying the edit. Checking and publishing must be serialized with removal or supersession.
4. An obsolete result is released without changing scene state. If C is still preparing, A can remain visible until C is ready; publishing B is not required.
5. Submitted work retains its dependencies until completion even when its result is no longer wanted.

The producer may serialize its edits or use a request identity. The engine need not publish every intermediate result in submission order.

## Two views render the same scene

**Required behavior:** views share scene resources while maintaining independent temporal state.

1. Two cameras render the same retained scene.
2. Each rendering request captures its camera and scene inputs and uses its own previous transforms and reconstruction history.
3. Moving or resetting one camera affects that view's history without resetting the other.
4. Shared scene resources remain alive through both views' outstanding work.

## A post effect is removed while frames are in flight

**Required behavior:** pass removal affects future frames without destroying resources used by accepted work.

1. An extension installs a post-processing pass that owns its pipelines and image resources.
2. A frame selects the pass and records its work using the stage's defined inputs and outputs.
3. The extension removes the pass. Subsequent frames no longer select it.
4. Accepted frames retain the pass resources they use through completion. Final release then permits their destruction.

## Renderer shutdown with outstanding work

**Required behavior:** shutdown prevents new publication and completes the lifetime of all accepted work before destroying the device resources it depends on.

1. The host stops producers and new render requests, and prevents completion callbacks from publishing further scene edits.
2. Producers, retained scene entries, and installed passes release their ownership claims.
3. Accepted compute and graphics work settles. Completion handling releases unwanted results, frame references, and remaining temporal history.
4. Resource destruction callbacks are drained before their required allocator and device services are destroyed.

Stopping publication does not imply that submitted GPU work has stopped using its resources.
