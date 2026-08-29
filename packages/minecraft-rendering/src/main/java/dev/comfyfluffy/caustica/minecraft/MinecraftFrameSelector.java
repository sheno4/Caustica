package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;

import java.util.Objects;

/** Immutable entry-scene and camera-medium selection for one active Minecraft program epoch. */
public final class MinecraftFrameSelector {
    private final SceneId scene;
    private final ViewMedium.Volume<?, ?> submergedMedium;

    public <B, N> MinecraftFrameSelector(SceneId scene, VolumeId<B, N> waterVolume,
                                         ShaderData<B> bindingData, ShaderData<N> instanceData) {
        this.scene = Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(waterVolume, "waterVolume");
        Objects.requireNonNull(bindingData, "bindingData");
        Objects.requireNonNull(instanceData, "instanceData");
        submergedMedium = new ViewMedium.Volume<>(waterVolume, bindingData, instanceData);
    }

    Selection select(boolean submerged) {
        return new Selection(scene, submerged ? submergedMedium : ViewMedium.Vacuum.INSTANCE);
    }

    record Selection(SceneId scene, ViewMedium medium) {}
}
