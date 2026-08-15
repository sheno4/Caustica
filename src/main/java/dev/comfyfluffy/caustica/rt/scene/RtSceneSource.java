package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import org.joml.Matrix4f;

import java.util.Objects;

/** Host-neutral environment and capture seam implemented by one registered scene provider. */
public interface RtSceneSource {
    /** The currently publishable retained scene, or {@code null} while the source is not ready. */
    Retained retainedScene();

    /** Submit CPU-captured frame geometry to the renderer-owned asynchronous geometry manager. */
    void submitFrame(GpuContext ctx, Retained retained, RtSceneGeometryManager geometry, Camera camera);

    int bindlessTextureCapacity();

    void resetBindlessTextures(int capacity);

    /** Populate a replacement pipeline from the current stable texture-slot mapping. */
    void rebindTextures(RtPipeline pipeline, long sampler);

    void uploadPendingTextures(RtPipeline pipeline, long sampler);

    /** Source-owned scene origin and finite-light segment for one renderer frame. */
    record Retained(SceneOrigin origin, RetainedLights retainedLights) {
        public Retained {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(retainedLights, "retainedLights");
        }
    }

    /** Camera inputs needed by a frame-varying geometry source. Matrices remain renderer-owned. */
    record Camera(double x, double y, double z, Matrix4f projection, Matrix4f viewRotation) {
        public Camera {
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(viewRotation, "viewRotation");
        }
    }

    /** Generic immutable finite-light segment consumed by the renderer's unified light scene. */
    record RetainedLights(long lightAddress, long nodeAddress, int rootNodeIndex,
                          int finiteLightCount, int linkedEmitterCount,
                          float rebaseOffsetX, float rebaseOffsetY, float rebaseOffsetZ,
                          float metersPerWorldUnit, long generation) {
    }
}
