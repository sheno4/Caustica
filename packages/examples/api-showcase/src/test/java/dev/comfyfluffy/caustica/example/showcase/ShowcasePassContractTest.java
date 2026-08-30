package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectGraphics;
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
    void worldResourcePassWaitsForProgramsAndPublicationVisibility() {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean submitted = new AtomicBoolean();
        AtomicBoolean visible = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger publishCalls = new java.util.concurrent.atomic.AtomicInteger();
        ShowcasePasses.WorldMeshHandoff handoff = new ShowcasePasses.WorldMeshHandoff() {
            @Override public boolean published() { return submitted.get() && visible.get(); }
            @Override public void recordAndPublish(dev.comfyfluffy.caustica.api.pass.PassFrame frame) {
                if (submitted.compareAndSet(false, true)) publishCalls.incrementAndGet();
            }
            @Override public void close() { closed.set(true); }
        };
        var pass = ShowcasePasses.worldResource(ready::get, handoff);

        pass.record(null);
        org.junit.jupiter.api.Assertions.assertFalse(submitted.get());
        ready.set(true);
        pass.record(null);
        pass.record(null);
        assertTrue(submitted.get());
        org.junit.jupiter.api.Assertions.assertFalse(handoff.published());
        visible.set(true);
        pass.record(null);
        pass.close();

        assertTrue(handoff.published());
        org.junit.jupiter.api.Assertions.assertEquals(1, publishCalls.get());
        assertTrue(closed.get());
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
        assertSame(ShaderObjectGraphics.class, ShowcasePasses.class.getDeclaredMethod(
                "createGraphicsShaders", GpuDevice.class, ByteBuffer.class, ByteBuffer.class).getReturnType());
        assertSame(ShaderObjectGraphics.class, ShowcasePasses.class.getDeclaredMethod(
                "createUiGraphicsShaders", GpuDevice.class, ByteBuffer.class, ByteBuffer.class).getReturnType());
    }

    @Test
    void uiSceneBindingReadsTheDescriptorIndexAtTheStartOfPushData() {
        var mapping = ShowcasePasses.UI_SCENE_MAPPING;
        assertEquals(0, mapping.descriptorSet());
        assertEquals(0, mapping.binding());
        assertEquals(0, mapping.pushDataOffset());
    }
}
