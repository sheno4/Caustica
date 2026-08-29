package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTlasMappingSourceTest {
    @Test
    void worldTlasUsesTheMappedBindingWhileImagesRemainHeapNative() throws IOException {
        String source = resource("/caustica/shaders/world/bindings.slang");
        assertTrue(source.contains("[[vk::binding(0, 0)]] RaytracingAccelerationStructure worldTopLevelAS;"));
        assertTrue(source.contains("return worldTopLevelAS;"));
        assertFalse(source.contains("return accelerationStructure(roots.resources.topLevelAS);"));
        assertTrue(source.contains("RWTexture2D<float4> image = ResourceDescriptorHeap"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = RtTlasMappingSourceTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing test resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
