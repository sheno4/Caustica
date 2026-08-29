package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MinecraftFrameSelectorTest {
    @Test
    void selectsOnlyAValidBoundVolumeWhileSubmerged() {
        SceneId scene = new SceneId() {};
        VolumeId<Object, Object> volume = new VolumeId<>() {};
        ShaderDataType<Object> binding = ShaderDataType.create("water binding");
        ShaderDataType<Object> instance = ShaderDataType.create("water instance");
        try (var ignored = MinecraftFrameSelector.install(
                scene, volume, binding.data(21L), instance.data(22L))) {
            assertNull(MinecraftFrameSelector.select(false).initialVolume());
            var submerged = MinecraftFrameSelector.select(true);
            assertSame(scene, submerged.scene());
            assertSame(volume, submerged.initialVolume().volume());
        }
    }

    @Test
    void zeroShaderDataSelectsVacuumAndLeaseRemovalClearsTheHandoff() {
        SceneId scene = new SceneId() {};
        VolumeId<Object, Object> volume = new VolumeId<>() {};
        ShaderDataType<Object> binding = ShaderDataType.create("water binding");
        ShaderDataType<Object> instance = ShaderDataType.create("water instance");
        var lease = MinecraftFrameSelector.install(scene, volume, binding.data(0L), instance.data(22L));

        assertNotNull(MinecraftFrameSelector.select(true));
        assertNull(MinecraftFrameSelector.select(true).initialVolume());
        lease.close();
        assertNull(MinecraftFrameSelector.select(true));
    }
}
