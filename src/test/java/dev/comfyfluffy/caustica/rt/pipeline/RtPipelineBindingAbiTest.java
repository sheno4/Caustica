package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_BASE_COLOR_TEXTURES;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_G_NORMAL;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_G_SPEC_MOTION;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_SET_BINDING_COUNT;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_SET_DESCRIPTOR_COUNT;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.WORLD_SET_SAMPLER_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPipelineBindingAbiTest {
    @Test
    void reflectionPreservesTheSetZeroBindingHole() {
        assertEquals(8, WORLD_SET_DESCRIPTOR_COUNT);
        assertEquals(9, WORLD_SET_BINDING_COUNT);
        assertEquals(0, WORLD_SET_SAMPLER_COUNT);
        assertEquals(3, WORLD_G_NORMAL);
        assertEquals(8, WORLD_G_SPEC_MOTION);
    }

    @Test
    void baseColorTextureArrayKeepsItsSetOneIndex() {
        assertEquals(0, WORLD_BASE_COLOR_TEXTURES);
    }
}
