package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.engine.scene.MeshPreparationBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

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

        Preparation(RetainedSceneSnapshot.Mesh mesh, RtPreparedMesh.State source) {
            this.mesh = mesh;
            this.source = source;
        }

        void record(org.lwjgl.vulkan.VkCommandBuffer command) {
            var build = mesh.build();
            nativeBuild = source != null && RtRetainedGeometryPlan.canRefitBlas(source.build, build)
                    ? RtAccel.preparePersistentBlasUpdate(context, source.operation,
                            build.positions().bytes().address(), build.indices().bytes().address(), "ready mesh " + mesh.identity())
                    : RtAccel.prepareUpdateablePersistentBlasBuild(context,
                            build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                            build.indices().bytes().address(), RtRetainedGeometryPlan.blasRanges(build), "ready mesh " + mesh.identity());
            owner = new RtPreparedMesh(context, build, nativeBuild);
            RtAccel.recordBlasBuilds(context, command, List.of(nativeBuild.op()));
        }

        void complete(GpuComputeCompletion completion) {
            if (owner == null) {
                Throwable failure = completion instanceof GpuComputeCompletion.Failed failed
                        ? failed.failure() : new CancellationException("mesh preparation cancelled");
                result.completeExceptionally(failure);
            } else {
                finish(result, owner, () -> RtAccel.freeBlasScratch(List.of(nativeBuild.op())), completion);
            }
        }
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
