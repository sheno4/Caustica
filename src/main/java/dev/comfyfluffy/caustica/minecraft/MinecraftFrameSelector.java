package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;

import java.util.Objects;

/** Immutable root-scene and camera-volume selection for one active Minecraft program epoch. */
public final class MinecraftFrameSelector {
    private final SceneId scene;
    private final FrameSnapshot.InitialVolume<?, ?> submergedVolume;

    public <B, N> MinecraftFrameSelector(SceneId scene, VolumeId<B, N> waterVolume,
                                         ShaderData<B> bindingData, ShaderData<N> instanceData) {
        this.scene = Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(waterVolume, "waterVolume");
        Objects.requireNonNull(bindingData, "bindingData");
        Objects.requireNonNull(instanceData, "instanceData");
        submergedVolume = bindingData.bits() == 0L || instanceData.bits() == 0L
                ? null : new FrameSnapshot.InitialVolume<>(waterVolume, bindingData, instanceData);
    }

    Selection select(boolean submerged) {
        return new Selection(scene, submerged ? submergedVolume : null);
    }

    record Selection(SceneId scene, FrameSnapshot.InitialVolume<?, ?> initialVolume) {}
}
