package dev.comfyfluffy.caustica.minecraft.client.overlay;

import dev.comfyfluffy.caustica.minecraft.client.TestProjectRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

final class OverlayHeapAbiTest {
    @Test void overlayShadersUsePushDataAndDescriptorHeaps() throws IOException {
        String block = source("shaders/pipelines/block_outline/fragment.frag.slang");
        String nameTag = source("shaders/pipelines/name_tag/fragment.frag.slang");
        String composite = source("shaders/pipelines/overlay_composite/glow.frag.slang");
        for (String shader : new String[] { nameTag, composite }) {
            assertTrue(shader.contains("[[vk::push_constant]]"));
            assertTrue(shader.contains("DescriptorHeap"));
            assertFalse(shader.contains("vk::binding"));
        }
        assertTrue(block.contains("[[vk::push_constant]]"));
        assertTrue(block.contains("[[vk::binding(0, 0)]]"));
        assertTrue(block.contains("TraceRayInline(sceneTlas"));
        assertFalse(block.contains("ResourceDescriptorHeap"));
        assertFalse(block.contains("NonUniformResourceIndex"));
        assertTrue(nameTag.contains("pc.textureIndex"));
        assertTrue(nameTag.contains("pc.samplerIndex"));
        assertTrue(composite.contains("pc.sourceImageIndex"));
    }

    @Test void blockOutlineMapsTheStaticTlasBindingToItsPushedDescriptorIndex() throws IOException {
        String feature = source("src/main/java/dev/comfyfluffy/caustica/minecraft/client/overlay/BlockOutlineFeature.java");
        assertTrue(feature.contains("TLAS_INDEX_OFFSET = 96"));
        assertTrue(feature.contains("fragmentAccelerationStructure(0, 0, TLAS_INDEX_OFFSET)"));
        assertTrue(feature.contains("push.putInt(TLAS_INDEX_OFFSET, tlasDescriptor)"));
    }

    @Test void deletedDescriptorBindingsAreNotImported() throws IOException {
        assertFalse(source("shaders/pipelines/block_outline/fragment.frag.slang").contains("import bindings"));
        assertFalse(source("shaders/pipelines/name_tag/fragment.frag.slang").contains("import bindings"));
        assertFalse(source("shaders/pipelines/overlay_composite/glow.frag.slang").contains("import bindings"));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(TestProjectRoot.resolve("packages/minecraft-client").resolve(relative));
    }
}
