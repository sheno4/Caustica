package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShowcasePassContractTest {
    @Test
    void postPlacementUsesTheProvenStageLocalBloomAnchor() {
        var post = (PassPlacement.After) ShowcasePasses.POST_EFFECT_PLACEMENT;

        assertSame(ShowcasePasses.BLOOM, post.anchor());
    }

    @Test
    void worldResourcePassRunsReadyPublicationOutsideTheGpuRecordingProbe() {
        AtomicBoolean published = new AtomicBoolean();
        var pass = ShowcasePasses.worldResource(null, () -> published.set(true));

        pass.record(null);

        assertTrue(published.get());
    }

    @Test
    void settingsReadUsesAFrameStableLookupSnapshot() {
        AtomicBoolean snapshotted = new AtomicBoolean();
        OptionLookup values = feature -> new OptionValues() {
            @Override public <T> T get(Option<T> option) {
                assertSame(ApiShowcaseExtension.ID, feature);
                assertSame(ApiShowcaseExtension.COLOUR_GRADE_STRENGTH, option);
                @SuppressWarnings("unchecked") T value = (T) Float.valueOf(0.75f);
                return value;
            }
        };
        OptionLookup lookup = new OptionLookup() {
            @Override public dev.comfyfluffy.caustica.settings.OptionValues options(
                    dev.comfyfluffy.caustica.settings.ResourceId featureId) {
                throw new AssertionError("live lookup must not be read directly");
            }

            @Override public OptionLookup snapshot() {
                snapshotted.set(true);
                return values;
            }
        };

        assertEquals(0.75f, ShowcasePasses.colourGradeStrength(lookup));
        assertTrue(snapshotted.get());
    }

    @Test
    void shaderObjectFactoryUsesThePublishedVulkanSupportType() throws Exception {
        assertNotNull(ShowcasePasses.class.getDeclaredMethod(
                "createComputeShader", GpuDevice.class, ByteBuffer.class));
        assertSame(ShaderObjectCompute.class, ShowcasePasses.class.getDeclaredMethod(
                "createComputeShader", GpuDevice.class, ByteBuffer.class).getReturnType());
    }
}
