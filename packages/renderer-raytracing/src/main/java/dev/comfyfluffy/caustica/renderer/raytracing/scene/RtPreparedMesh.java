package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;

/** Strong native ownership of one completed, immutable bottom-level acceleration structure. */
final class RtPreparedMesh implements ResourceOwner {
    private final State state;
    private boolean closed;

    RtPreparedMesh(VulkanDeviceContext context, MeshBuild<?> build, RtAccel.PersistentBuild nativeBuild) {
        state = new State(context, build, nativeBuild);
    }
    private RtPreparedMesh(State state) { this.state = state; }
    State value() { return state; }
    @Override public ResourceRef reference() { return state; }
    @Override public ResourceOwner retain() {
        synchronized (state) {
            if (closed) throw new IllegalStateException("native mesh claim is closed");
            return state.retain();
        }
    }
    @Override public void close() {
        synchronized (state) {
            if (closed) return;
            closed = true;
            if (--state.references == 0) state.context.deferDestroy(
                    () -> RtAccel.destroyCallerOwnedAccel(state.accel, state.backing));
        }
    }

    static final class State implements ResourceRef {
        final VulkanDeviceContext context;
        final MeshBuild<?> build;
        final RtAccel.PreparedBlas operation;
        final RtAccel accel;
        final GpuBuffer backing;
        private int references = 1;
        State(VulkanDeviceContext context, MeshBuild<?> build, RtAccel.PersistentBuild nativeBuild) {
            this.context = context;
            this.build = build;
            operation = nativeBuild.op();
            accel = nativeBuild.accel();
            backing = nativeBuild.backing();
        }
        @Override public synchronized ResourceOwner retain() {
            if (references == 0) throw new IllegalStateException("native mesh is released");
            references++;
            return new RtPreparedMesh(this);
        }
    }
}
