package dev.comfyfluffy.caustica.nvidia.nrd;

import dev.comfyfluffy.caustica.renderer.denoising.DenoiserCommonSettings;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserImage;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserInputs;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NrdAbiTest {
    @Test
    void writesPinnedCreateAndResourceLayouts() {
        try (Arena arena = Arena.ofConfined()) {
            var create = arena.allocate(NrdAbi.CREATE_SIZE, 8);
            NrdAbi.writeCreate(create, new NrdDevice(11, 22, 33, 4, 3), NrdMethod.REBLUR_DIFFUSE_SPECULAR, 1920, 1080);
            assertEquals(33, create.get(ValueLayout.JAVA_LONG, 16));
            assertEquals(4, create.get(ValueLayout.JAVA_INT, 24));
            assertEquals(4, new NrdDevice(11, 22, 33, 4, 3).graphicsQueueFamily());
            assertEquals(1, create.get(ValueLayout.JAVA_INT, 40));

            var resources = arena.allocate(NrdAbi.RESOURCES_SIZE, 8);
            DenoiserExtent extent = new DenoiserExtent(1920, 1080);
            DenoiserImage image = new DenoiserImage(101, 97, 1, extent);
            NrdAbi.writeResources(resources, new DenoiserInputs(image, image, image, image, image, image, image,
                    Optional.empty(), Optional.of(new DenoiserImage(202, 109, 1, extent))));
            assertEquals(0, resources.get(ValueLayout.JAVA_LONG, 7 * NrdAbi.IMAGE_SIZE));
            assertEquals(202, resources.get(ValueLayout.JAVA_LONG, 8 * NrdAbi.IMAGE_SIZE));
        }
    }

    @Test
    void writesCommonSettingsFlagsAtStableOffsets() {
        try (Arena arena = Arena.ofConfined()) {
            var common = arena.allocate(NrdAbi.COMMON_SIZE, 4);
            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            var settings = new DenoiserCommonSettings(identity, identity, identity, identity,
                    1, 2, 3, 4, 5, 6, 7, 100, .01f, .02f, 16, 42,
                    true, DenoiserReset.CLEAR_AND_RESTART);
            var image = new DenoiserImage(101, 97, 1, new DenoiserExtent(1920, 1080));
            for (int optionalFlags = 0; optionalFlags < 4; optionalFlags++) {
                var inputs = new DenoiserInputs(image, image, image, image, image, image, image,
                        (optionalFlags & 1) != 0 ? Optional.of(image) : Optional.empty(),
                        (optionalFlags & 2) != 0 ? Optional.of(image) : Optional.empty());
                NrdAbi.writeCommon(common, new DenoiserFrame(1, settings, inputs));
                assertEquals(9 | (optionalFlags << 1), common.get(ValueLayout.JAVA_INT, 304));
            }
            assertEquals(1.0f, common.get(ValueLayout.JAVA_FLOAT, 0));
            assertEquals(100.0f, common.get(ValueLayout.JAVA_FLOAT, 284));
            assertEquals(42, common.get(ValueLayout.JAVA_INT, 300));
        }
    }
}
