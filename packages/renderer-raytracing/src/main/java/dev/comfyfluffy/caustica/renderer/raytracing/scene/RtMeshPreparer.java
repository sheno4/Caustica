package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.engine.scene.MeshPreparationBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.StackTrace;
import jdk.jfr.DataAmount;

/** Builds ready mesh revisions independently of scene edits. Inputs and source remain borrowed through completion. */
public final class RtMeshPreparer implements MeshPreparationBackend {
    private final VulkanDeviceContext context;
    public RtMeshPreparer(VulkanDeviceContext context) { this.context = context; }

    @Override public CompletableFuture<ResourceOwner> prepare(RetainedSceneSnapshot.Mesh mesh, ResourceOwner source) {
        RtPreparedMesh.State previous = source == null ? null : ((RtPreparedMesh) source).value();
        if (previous != null && RtRetainedGeometryPlan.canReuseBlas(previous.build, mesh.build())) {
            return CompletableFuture.completedFuture(source.retain());
        }
        Preparation preparation = new Preparation(mesh, previous);
        try {
            context.gpuExecutor().submit(preparation::record, preparation::complete);
        } catch (Throwable failure) {
            preparation.result.completeExceptionally(failure);
        }
        return preparation.result;
    }

    private final class Preparation {
        final RetainedSceneSnapshot.Mesh mesh;
        final RtPreparedMesh.State source;
        final CompletableFuture<ResourceOwner> result = new CompletableFuture<>();
        RtAccel.PersistentBuild nativeBuild;
        RtPreparedMesh owner;
        RtAccel.CompactionQuery query;
        RtPreparedMesh compactedOwner;
        long compactedSize;

        Preparation(RetainedSceneSnapshot.Mesh mesh, RtPreparedMesh.State source) {
            this.mesh = mesh;
            this.source = source;
        }

        void record(org.lwjgl.vulkan.VkCommandBuffer command) {
            var build = mesh.build();
            String label = "ready mesh " + mesh.identity();
            if (build.buildPolicy() == MeshBuild.BuildPolicy.STATIC) {
                nativeBuild = RtAccel.preparePersistentBlasBuild(context,
                        build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                        build.indices().bytes().address(), RtRetainedGeometryPlan.blasRanges(build), label);
            } else if (source != null && RtRetainedGeometryPlan.canRefitBlas(source.build, build)) {
                nativeBuild = RtAccel.preparePersistentBlasUpdate(context, source.operation, source.accel.handle,
                        build.positions().bytes().address(), build.indices().bytes().address(), label);
            } else {
                nativeBuild = RtAccel.prepareUpdateablePersistentBlasBuild(context,
                            build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                            build.indices().bytes().address(), RtRetainedGeometryPlan.blasRanges(build), label);
            }
            owner = new RtPreparedMesh(context, build, nativeBuild);
            RtAccel.recordBlasBuilds(context, command, List.of(nativeBuild.op()));
            if (build.buildPolicy() == MeshBuild.BuildPolicy.STATIC) {
                query = new RtAccel.CompactionQuery(context);
                query.record(command, nativeBuild.accel());
            }
        }

        void complete(GpuComputeCompletion completion) {
            if (owner == null) {
                Throwable failure = completion instanceof GpuComputeCompletion.Failed failed
                        ? failed.failure() : new CancellationException("mesh preparation cancelled");
                result.completeExceptionally(failure);
            } else if (query != null && completion instanceof GpuComputeCompletion.Succeeded && !result.isCancelled()) {
                try {
                    compactedSize = query.readSize();
                } catch (Throwable failure) {
                    finish(result, owner, this::releaseBuildResources, new GpuComputeCompletion.Failed(failure));
                    return;
                }
                if (compactedSize < nativeBuild.backing().size()) {
                    try {
                        releaseBuildResources();
                        // The future keeps input ownership borrowed until the compacting copy completes.
                        context.gpuExecutor().submit(this::recordCompaction, this::completeCompaction);
                    } catch (Throwable failure) {
                        finish(result, owner, () -> {}, new GpuComputeCompletion.Failed(failure));
                    }
                } else {
                    recordCompactionSize(nativeBuild.backing().size());
                    finish(result, owner, this::releaseBuildResources, completion);
                }
            } else {
                finish(result, owner, this::releaseBuildResources, completion);
            }
        }

        void releaseBuildResources() {
            try { RtAccel.freeBlasScratch(List.of(nativeBuild.op())); }
            finally { if (query != null) query.close(); }
        }

        void recordCompaction(org.lwjgl.vulkan.VkCommandBuffer command) {
            String label = "ready mesh " + mesh.identity() + " compact";
            RtAccel.CompactedBlas compacted = RtAccel.prepareCompactedBlas(context, compactedSize, label);
            compactedOwner = new RtPreparedMesh(context, mesh.build(), nativeBuild.op().operation(),
                    compacted.accel(), compacted.backing());
            RtAccel.recordCompaction(context, command, nativeBuild.accel(), compacted.accel(), label);
        }

        void completeCompaction(GpuComputeCompletion completion) {
            if (completion instanceof GpuComputeCompletion.Succeeded) recordCompactionSize(compactedSize);
            finishCompaction(result, owner, compactedOwner, completion);
        }

        void recordCompactionSize(long retainedBytes) {
            BlasCompactionEvent event = new BlasCompactionEvent();
            if (!event.isEnabled()) return;
            event.meshId = mesh.identity();
            event.builtBytes = nativeBuild.backing().size();
            event.retainedBytes = retainedBytes;
            event.commit();
        }
    }

    /** Both generations remain owned until the copy terminates; only the destination is published. */
    static void finishCompaction(CompletableFuture<ResourceOwner> result, ResourceOwner source,
                                 ResourceOwner destination, GpuComputeCompletion completion) {
        if (destination == null) {
            finish(result, source, () -> {}, completion);
        } else {
            finish(result, destination, source::close, completion);
        }
    }

    @Name("dev.comfyfluffy.caustica.BlasCompaction")
    @Label("Static BLAS compaction")
    @Category({"Caustica", "Mesh"}) @StackTrace(false) @Enabled(false)
    static final class BlasCompactionEvent extends Event {
        public long meshId;
        @DataAmount(DataAmount.BYTES)
        public long builtBytes;
        @DataAmount(DataAmount.BYTES)
        public long retainedBytes;
    }

    /** A ready result is delivered only after scratch disposal and GPU completion; cancelled consumers release it. */
    static void finish(CompletableFuture<ResourceOwner> result, ResourceOwner owner, Runnable releaseScratch,
                       GpuComputeCompletion completion) {
        Throwable failure = switch (completion) {
            case GpuComputeCompletion.Succeeded ignored -> null;
            case GpuComputeCompletion.Cancelled ignored -> new CancellationException("mesh preparation cancelled");
            case GpuComputeCompletion.Failed failed -> failed.failure();
        };
        try { releaseScratch.run(); }
        catch (Throwable cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) {
            RtRetainedSceneBackend.suppressCleanupFailure(failure, owner::close);
            result.completeExceptionally(failure);
        } else if (!result.complete(owner)) owner.close();
    }
}
