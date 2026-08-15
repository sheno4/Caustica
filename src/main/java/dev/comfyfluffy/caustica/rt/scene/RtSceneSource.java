package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

import java.util.Objects;

/** Host-neutral environment and capture seam implemented by one registered scene provider. */
public interface RtSceneSource {
    /** The currently publishable retained scene, or {@code null} while the source is not ready. */
    Retained retainedScene();

    int bindlessTextureCapacity();

    void resetBindlessTextures(int capacity);

    /** Populate a replacement pipeline from the current stable texture-slot mapping. */
    void rebindTextures(RtPipeline pipeline, long sampler);

    void uploadPendingTextures(RtPipeline pipeline, long sampler);

    /** Resolve a neutral texture identity into this source's stable bindless slot. */
    default int bindlessTextureSlot(SceneMesh.TextureReference texture) {
        return 0;
    }

    /** Source-owned scene origin and finite-light segment for one renderer frame. */
    record Retained(SceneOrigin origin, RetainedLights retainedLights) {
        public Retained {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(retainedLights, "retainedLights");
        }
    }

    /** Generic immutable finite-light segment consumed by the renderer's unified light scene. */
    record RetainedLights(long lightAddress, long nodeAddress, int rootNodeIndex,
                          int finiteLightCount, int linkedEmitterCount,
                          float rebaseOffsetX, float rebaseOffsetY, float rebaseOffsetZ,
                          float metersPerWorldUnit, long generation) {
    }
}
