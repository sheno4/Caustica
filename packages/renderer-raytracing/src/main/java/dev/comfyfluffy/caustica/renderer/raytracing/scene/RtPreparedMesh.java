package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.support.SharedResource;

/** Strong native ownership of one completed, immutable bottom-level acceleration structure. */
final class RtPreparedMesh implements ResourceOwner {
    private final State state;
    private final SharedResource<State> owner;

    RtPreparedMesh(VulkanDeviceContext context, MeshBuild<?> build, RtAccel.PersistentBuild nativeBuild) {
        this(new State(context, build, nativeBuild).initial);
    }
    private RtPreparedMesh(SharedResource<State> owner) {
        this.owner = owner;
        state = owner.get();
    }
    State value() { return owner.get(); }
    @Override public ResourceRef reference() { return state; }
    @Override public ResourceOwner retain() { return new RtPreparedMesh(owner.retain()); }
    @Override public void close() { owner.close(); }

    static final class State implements ResourceRef {
        final VulkanDeviceContext context;
        final MeshBuild<?> build;
        final RtAccel.PreparedBlas operation;
        final RtAccel accel;
        final GpuBuffer backing;
        final SharedResource<State> initial;
        final SharedResource.Reference<State> lifetime;
        State(VulkanDeviceContext context, MeshBuild<?> build, RtAccel.PersistentBuild nativeBuild) {
            this.context = context;
            this.build = build;
            operation = nativeBuild.op();
            accel = nativeBuild.accel();
            backing = nativeBuild.backing();
            initial = SharedResource.owned(this, ignored -> context.deferDestroy(
                    () -> RtAccel.destroyCallerOwnedAccel(accel, backing)));
            lifetime = initial.reference();
        }
        @Override public ResourceOwner retain() {
            return new RtPreparedMesh(lifetime.retain());
        }
    }
}
