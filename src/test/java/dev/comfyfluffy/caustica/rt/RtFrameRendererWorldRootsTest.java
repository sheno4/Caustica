package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.program.ProgramResolution;
import dev.comfyfluffy.caustica.rt.pipeline.RtBindings;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtFrameRendererWorldRootsTest {
    @Test
    void activeInitialVolumeWritesTypedImplementationAndData() {
        ShaderDataType<Object> binding = ShaderDataType.create("binding");
        ShaderDataType<Object> instance = ShaderDataType.create("instance");
        FrameSnapshot.InitialVolume<Object, Object> initial = new FrameSnapshot.InitialVolume<>(
                new VolumeId<>() { }, binding.data(0x1234L), instance.data(0x5678L));
        ByteBuffer roots = roots((byte) 0x5a);

        RtFrameRenderer.writeInitialVolumeRoots(roots, initial,
                new ProgramResolution.ActiveVolume(7));

        assertEquals(7, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET));
        assertEquals(1, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET));
        assertEquals(0x1234L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET));
        assertEquals(0x5678L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET));
        assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET));
    }

    @Test
    void staleInitialVolumeResolvesToExplicitVacuum() {
        ByteBuffer roots = roots((byte) 0x5a);

        RtFrameRenderer.writeInitialVolumeRoots(roots, null, ProgramResolution.Vacuum.INSTANCE);

        assertEquals(0, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET));
        assertEquals(0, roots.getInt(RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET));
        assertEquals(0L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET));
        assertEquals(0L, roots.getLong(RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET));
        assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET));
    }

    private static ByteBuffer roots(byte fill) {
        ByteBuffer roots = ByteBuffer.allocate(RtBindings.WORLD_PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < roots.capacity(); i++) roots.put(i, fill);
        return roots;
    }
}
