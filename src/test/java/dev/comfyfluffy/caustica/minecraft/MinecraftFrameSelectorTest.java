package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;

final class MinecraftFrameSelectorTest {
    @Test
    void selectsOnlyAValidBoundVolumeWhileSubmerged() {
        SceneId scene = new TestScene();
        VolumeId<Object, Object> volume = new TestVolume<>();
        ShaderDataType<Object> binding = ShaderDataType.create("water binding");
        ShaderDataType<Object> instance = ShaderDataType.create("water instance");
        var selector = new MinecraftFrameSelector(
                scene, volume, binding.data(21L), instance.data(22L));

        assertNull(selector.select(false).initialVolume());
        var submerged = selector.select(true);
        assertSame(scene, submerged.scene());
        assertSame(volume, submerged.initialVolume().volume());
    }

    @Test
    void zeroShaderDataSelectsVacuum() {
        SceneId scene = new TestScene();
        VolumeId<Object, Object> volume = new TestVolume<>();
        ShaderDataType<Object> binding = ShaderDataType.create("water binding");
        ShaderDataType<Object> instance = ShaderDataType.create("water instance");
        var selector = new MinecraftFrameSelector(
                scene, volume, binding.data(0L), instance.data(22L));

        assertSame(scene, selector.select(true).scene());
        assertNull(selector.select(true).initialVolume());
    }

    @Test
    void selectorHasNoStaticEpochState() {
        assertTrue(Arrays.stream(MinecraftFrameSelector.class.getDeclaredFields())
                .noneMatch(field -> Modifier.isStatic(field.getModifiers())));
    }

    private static final class TestScene implements SceneId { }
    private static final class TestVolume<B, N> implements VolumeId<B, N> { }
}
