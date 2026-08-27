package dev.comfyfluffy.caustica.api.program;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProgramAbiTest {
    @Test
    void slangAndJavaExposeTheSameAbiVersionAndRequiredDispatchFacts() throws IOException {
        String types = resource("caustica_types.slang");
        String coverage = resource("caustica_coverage.slang");
        String composition = resource("caustica_api.slang");
        String modifier = resource("caustica_surface_modifier.slang");
        String resources = resource("caustica_resources.slang");

        assertTrue(types.contains("CAUSTICA_SHADER_ABI_VERSION = " + ProgramAbi.VERSION + "u"));
        assertTrue(types.contains("float2 barycentrics"));
        assertTrue(types.contains("AffineTransform objectToScene"));
        assertTrue(types.contains("NormalTransform normalToScene"));
        assertTrue(types.contains("GEOMETRY_PROJECTED_SURFACE_MODIFIER_RECEIVER = 0x1u"));
        assertTrue(types.contains("ShaderRootData shaderRoot"));
        assertTrue(types.contains("bool geometry_thin_walled = false"));
        assertTrue(coverage.contains("float2 barycentrics"));
        assertTrue(composition.contains("associatedtype Environments : IEnvironmentDispatch"));
        assertTrue(modifier.contains("inout OpenPbrSurface material"));
        assertTrue(resources.contains("ResourceDescriptorHeap[NonUniformResourceIndex(index.value)]"));
        assertTrue(resources.contains("SamplerDescriptorHeap[NonUniformResourceIndex(index.value)]"));
    }

    private static String resource(String name) throws IOException {
        String path = "/caustica/shaders/api/" + name;
        try (var input = ProgramAbiTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing public shader module " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
