package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtFrameRendererWorldRootsTest {
    @Test
    void spatialBindingWritesIndependentWordsAndClearsAnInactiveRevision() {
        var type = ShaderDataType.<Object>create("spatial");
        var spatial = new dev.comfyfluffy.caustica.api.view.SpatialMedium<>(
                new VolumeId<Object, Object>() { }, type.data(123), type.data(456), 8, 16, -24);
        ByteBuffer roots = roots((byte) 0x5a);
        RtFrameRenderer.writeSpatialMediumRoots(roots, spatial, 9, new dev.comfyfluffy.caustica.engine.scene.SceneOrigin(32, 64, -96));
        assertEquals(123, roots.getLong(RtBindings.WORLD_SPATIAL_MEDIUM_BINDING_DATA_OFFSET));
        assertEquals(456, roots.getLong(RtBindings.WORLD_SPATIAL_MEDIUM_INSTANCE_DATA_OFFSET));
        assertEquals(9, roots.getInt(RtBindings.WORLD_SPATIAL_MEDIUM_IMPLEMENTATION_OFFSET));
        assertEquals(1, roots.getInt(RtBindings.WORLD_SPATIAL_MEDIUM_ACTIVE_OFFSET));
        assertEquals(24, roots.getFloat(RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_X_OFFSET));
        assertEquals(48, roots.getFloat(RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Y_OFFSET));
        assertEquals(-72, roots.getFloat(RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Z_OFFSET));
        assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET));
        RtFrameRenderer.writeSpatialMediumRoots(roots, spatial, 0, dev.comfyfluffy.caustica.engine.scene.SceneOrigin.ZERO);
        assertEquals(0, roots.getLong(RtBindings.WORLD_SPATIAL_MEDIUM_BINDING_DATA_OFFSET));
        assertEquals(0, roots.getLong(RtBindings.WORLD_SPATIAL_MEDIUM_INSTANCE_DATA_OFFSET));
        assertEquals(0, roots.getInt(RtBindings.WORLD_SPATIAL_MEDIUM_IMPLEMENTATION_OFFSET));
        assertEquals(0, roots.getInt(RtBindings.WORLD_SPATIAL_MEDIUM_ACTIVE_OFFSET));
        assertEquals(0, roots.getFloat(RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_X_OFFSET));
        assertEquals(0, roots.getFloat(RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Y_OFFSET));
        assertEquals(0, roots.getFloat(RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Z_OFFSET));
    }

    @Test
    void activeInitialVolumeWritesTypedImplementationAndData() {
        ShaderDataType<Object> binding = ShaderDataType.create("binding");
        ShaderDataType<Object> instance = ShaderDataType.create("instance");
        ViewMedium.Volume<Object, Object> initial = new ViewMedium.Volume<>(
                new VolumeId<>() { }, binding.data(0x1234L), instance.data(0x5678L));
        ByteBuffer roots = roots((byte) 0x5a);

        RtFrameRenderer.writeInitialVolumeRoots(roots, initial, 7);

        assertEquals(7, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET));
        assertEquals(1, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET));
        assertEquals(0x1234L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET));
        assertEquals(0x5678L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET));
        assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET));
    }

    @Test
    void staleInitialVolumeResolvesToExplicitVacuum() {
        ByteBuffer roots = roots((byte) 0x5a);

        RtFrameRenderer.writeInitialVolumeRoots(
                roots, ViewMedium.Vacuum.INSTANCE, 0);

        assertEquals(0, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET));
        assertEquals(0, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET));
        assertEquals(0L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET));
        assertEquals(0L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET));
        assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET));
    }

    @Test
    void activeEnvironmentWritesTypedImplementationAndBindingData() {
        ShaderDataType<Object> type = ShaderDataType.create("environment binding");
        EnvironmentBinding<Object> binding = new EnvironmentBinding<>(
                new EnvironmentId<>() { }, type.data(0x1234_5678L));

        RtFrameRenderer.EnvironmentPush environment = RtFrameRenderer.environmentPush(binding, 9);

        assertEquals(9, environment.implementation());
        assertEquals(0x1234_5678L, environment.bindingData());
    }

    @Test
    void absentEnvironmentUsesResourceFreeBuiltin() {
        RtFrameRenderer.EnvironmentPush environment = RtFrameRenderer.environmentPush(null, 0);

        assertEquals(0, environment.implementation());
        assertEquals(0L, environment.bindingData());
    }

    @Test
    void staleEnvironmentUsesVisibleErrorWithoutReadingBindingData() {
        ShaderDataType<Object> type = ShaderDataType.create("stale environment binding");
        EnvironmentBinding<Object> binding = new EnvironmentBinding<>(
                new EnvironmentId<>() { }, type.data(0x1234_5678L));

        RtFrameRenderer.EnvironmentPush environment = RtFrameRenderer.environmentPush(binding, 0);

        assertEquals(0, environment.implementation());
        assertEquals(0L, environment.bindingData());
    }

    private static ByteBuffer roots(byte fill) {
        ByteBuffer roots = ByteBuffer.allocate(RtBindings.WORLD_PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < roots.capacity(); i++) roots.put(i, fill);
        return roots;
    }
}
