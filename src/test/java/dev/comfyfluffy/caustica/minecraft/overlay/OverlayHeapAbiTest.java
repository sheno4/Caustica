package dev.comfyfluffy.caustica.minecraft.overlay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class OverlayHeapAbiTest {
    @Test void overlayShadersUsePushDataAndDescriptorHeaps() throws IOException {
        String block = source("shaders/pipelines/block_outline/fragment.frag.slang");
        String nameTag = source("shaders/pipelines/name_tag/fragment.frag.slang");
        String composite = source("shaders/pipelines/overlay_composite/glow.frag.slang");
        for (String shader : new String[] { block, nameTag, composite }) {
            assertTrue(shader.contains("[[vk::push_constant]]"));
            assertTrue(shader.contains("DescriptorHeap"));
            assertFalse(shader.contains("vk::binding"));
        }
        assertTrue(block.contains("pc.tlasIndex"));
        assertTrue(nameTag.contains("pc.textureIndex"));
        assertTrue(nameTag.contains("pc.samplerIndex"));
        assertTrue(composite.contains("pc.sourceImageIndex"));
    }

    @Test void deletedDescriptorBindingsAreNotImported() throws IOException {
        assertFalse(source("shaders/pipelines/block_outline/fragment.frag.slang").contains("import bindings"));
        assertFalse(source("shaders/pipelines/name_tag/fragment.frag.slang").contains("import bindings"));
        assertFalse(source("shaders/pipelines/overlay_composite/glow.frag.slang").contains("import bindings"));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), relative));
    }
}
