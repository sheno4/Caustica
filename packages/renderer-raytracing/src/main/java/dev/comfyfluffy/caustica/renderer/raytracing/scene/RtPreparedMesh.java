package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.support.SharedResource;

/** Strong native ownership of one completed, immutable bottom-level acceleration structure. */
final class RtPreparedMesh implements ResourceOwner {
    private final SharedResource<State> owner;

    RtPreparedMesh(VulkanDeviceContext context, MeshBuild<?> build, RtAccel.PersistentBuild nativeBuild) {
        this(context, build, nativeBuild.operation(), nativeBuild.accel());
    }

    RtPreparedMesh(VulkanDeviceContext context, MeshBuild<?> build, RtAccel.BlasOperation operation,
                   RtAccel accel) {
        this(SharedResource.owned(new State(build, operation, accel),
                state -> context.deferDestroy(state.accel::destroy)));
    }

    private RtPreparedMesh(SharedResource<State> owner) {
        this.owner = owner;
    }

    State value() {
        return owner.get();
    }

    @Override
    public ResourceOwner retain() {
        return new RtPreparedMesh(owner.retain());
    }

    @Override
    public void close() {
        owner.close();
    }

    static final class State {
        final MeshBuild<?> build;
        final RtAccel.BlasOperation operation;
        final RtAccel accel;

        State(MeshBuild<?> build, RtAccel.BlasOperation operation, RtAccel accel) {
            this.build = build;
            this.operation = operation;
            this.accel = accel;
        }
    }
}
