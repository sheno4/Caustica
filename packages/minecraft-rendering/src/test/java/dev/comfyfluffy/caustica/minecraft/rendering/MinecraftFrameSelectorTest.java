package dev.comfyfluffy.caustica.minecraft.rendering;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertSame(ViewMedium.Vacuum.INSTANCE, selector.select(false).medium());
        var submerged = selector.select(true);
        assertSame(scene, submerged.scene());
        assertSame(volume, assertInstanceOf(ViewMedium.Volume.class, submerged.medium()).implementation());
    }

    @Test
    void zeroShaderDataRemainsAValidVolumeBinding() {
        SceneId scene = new TestScene();
        VolumeId<Object, Object> volume = new TestVolume<>();
        ShaderDataType<Object> binding = ShaderDataType.create("water binding");
        ShaderDataType<Object> instance = ShaderDataType.create("water instance");
        var selector = new MinecraftFrameSelector(
                scene, volume, binding.data(0L), instance.data(22L));

        assertSame(scene, selector.select(true).scene());
        ViewMedium.Volume<?, ?> medium = assertInstanceOf(
                ViewMedium.Volume.class, selector.select(true).medium());
        assertSame(volume, medium.implementation());
        assertEquals(0L, medium.bindingData().bits());
    }

    @Test
    void selectorHasNoStaticEpochState() {
        assertTrue(Arrays.stream(MinecraftFrameSelector.class.getDeclaredFields())
                .noneMatch(field -> Modifier.isStatic(field.getModifiers())));
    }

    private static final class TestScene implements SceneId { }
    private static final class TestVolume<B, N> implements VolumeId<B, N> { }
}
