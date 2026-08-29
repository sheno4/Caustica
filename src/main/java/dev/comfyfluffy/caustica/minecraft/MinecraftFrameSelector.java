package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;

import java.util.Objects;

/** Session-to-render-thread handoff for the root scene and Minecraft water volume binding. */
public final class MinecraftFrameSelector {
    private static volatile Binding<?, ?> binding;

    private MinecraftFrameSelector() {}

    public static <B, N> Lease install(SceneId scene, VolumeId<B, N> waterVolume,
                                       ShaderData<B> bindingData, ShaderData<N> instanceData) {
        Binding<B, N> installed = new Binding<>(scene, waterVolume, bindingData, instanceData);
        binding = installed;
        return () -> {
            if (binding == installed) binding = null;
        };
    }

    static Selection select(boolean submerged) {
        Binding<?, ?> current = binding;
        if (current == null) return null;
        return new Selection(current.scene, submerged ? current.initialVolumeOrNull() : null);
    }

    public interface Lease extends AutoCloseable {
        @Override void close();
    }

    record Selection(SceneId scene, FrameSnapshot.InitialVolume<?, ?> initialVolume) {}

    private record Binding<B, N>(SceneId scene, VolumeId<B, N> volume,
                                 ShaderData<B> bindingData, ShaderData<N> instanceData) {
        Binding {
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(volume, "volume");
            Objects.requireNonNull(bindingData, "bindingData");
            Objects.requireNonNull(instanceData, "instanceData");
        }

        FrameSnapshot.InitialVolume<B, N> initialVolumeOrNull() {
            if (bindingData.bits() == 0L || instanceData.bits() == 0L) return null;
            return new FrameSnapshot.InitialVolume<>(volume, bindingData, instanceData);
        }
    }
}
