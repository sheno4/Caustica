package dev.comfyfluffy.caustica.spi.host;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;

import java.util.Objects;

/**
 * First-party renderer-host adapter for retained scene state, texture discovery, and material publication.
 * This host integration SPI is not supported extension API.
 */
public interface RendererSceneSource {
    /** The currently publishable retained scene, or {@code null} while the source is not ready. */
    RetainedScene retainedScene();

    int bindlessTextureCapacity();

    void resetBindlessTextures(int capacity);

    /** Populate replacement renderer descriptors from the current stable texture-slot mapping. */
    void rebindTextures(BaseColorTextureSink textures, long sampler);

    void uploadPendingTextures(BaseColorTextureSink textures, long sampler);

    /** Publish the immutable material epoch used by host-side scene workers. */
    void publishMaterials(MaterialEpochView materials);

    /** Make the closing material epoch unavailable before host workers dispatch more work. */
    void clearMaterials();

    /** Resolve a neutral texture identity into this source's stable bindless slot. */
    default int bindlessTextureSlot(SceneMesh.TextureReference texture) {
        return 0;
    }

    /** Source-owned scene origin and finite-light segment for one renderer frame. */
    record RetainedScene(SceneOrigin origin, RetainedLights retainedLights) {
        public RetainedScene {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(retainedLights, "retainedLights");
        }
    }

    /** Immutable finite-light segment consumed by the renderer's unified light scene. */
    record RetainedLights(long lightAddress, long nodeAddress, int rootNodeIndex,
                          int finiteLightCount, int linkedEmitterCount,
                          float rebaseOffsetX, float rebaseOffsetY, float rebaseOffsetZ,
                          float metersPerWorldUnit, long generation) {
    }
}
