package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_PATH_QUEUE_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_PUSH_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_PUSH_CONSTANT_SIZE;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtPipelineBindingAbiTest {
    @Test
    void reflectionPreservesDeviceAddressRootsBeforeHeapIndices() {
        assertEquals(0, WORLD_PUSH_ADDRESS_OFFSET);
        assertEquals(8, WORLD_COMPOSITION_DATA_ADDRESS_OFFSET);
        assertEquals(16, WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET);
        assertEquals(48, WORLD_PATH_QUEUE_ADDRESS_OFFSET);
        assertEquals(56, WORLD_TOP_LEVEL_AS_INDEX_OFFSET);
        assertEquals(60, WORLD_OUTPUT_IMAGE_INDEX_OFFSET);
        assertEquals(84, WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET);
        assertEquals(88, WORLD_PUSH_CONSTANT_SIZE);
    }

    @Test
    void worldLayoutContainsNoDescriptorSetOrProviderTextureConstants() {
        var names = java.util.Arrays.stream(RtBindings.class.getFields())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> name.startsWith("WORLD_"))
                .toList();
        assertFalse(names.stream().anyMatch(name -> name.contains("SET")));
        assertFalse(names.stream().anyMatch(name -> name.contains("PROVIDER_TEXTURE")));
    }
}
