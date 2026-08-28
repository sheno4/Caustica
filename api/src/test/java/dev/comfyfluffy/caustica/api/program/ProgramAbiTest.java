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
        String surface = resource("caustica_surface.slang");
        String volume = resource("caustica_volume.slang");
        String resources = resource("caustica_resources.slang");

        assertTrue(types.contains("CAUSTICA_SHADER_ABI_VERSION = " + ProgramAbi.VERSION + "u"));
        assertTrue(types.contains("float2 barycentrics"));
        assertTrue(types.contains("AffineTransform objectToScene"));
        assertTrue(types.contains("NormalTransform normalToScene"));
        assertTrue(types.contains("ShaderRootData shaderRoot"));
        assertTrue(types.contains("uint64_t surfaceData"));
        assertTrue(types.contains("uint64_t volumeData"));
        assertTrue(types.contains("public struct VolumeInput"));
        assertTrue(types.contains("public struct VolumeProperties"));
        assertTrue(!types.contains("materialData"));
        assertTrue(!types.contains("Medium"));
        assertTrue(types.contains("bool geometry_thin_walled = false"));
        assertTrue(coverage.contains("float2 barycentrics"));
        assertTrue(coverage.contains("uint64_t surfaceData"));
        assertTrue(!coverage.contains("geometrySemantics"));
        assertTrue(composition.contains("associatedtype Environments : IEnvironmentDispatch"));
        assertTrue(composition.contains("associatedtype Volumes : IVolumeDispatch"));
        assertTrue(!composition.contains("SurfaceModifiers"));
        assertTrue(!surface.contains("evaluateMedium"));
        assertTrue(volume.contains("public interface IVolumeModel"));
        assertTrue(volume.contains("VolumeProperties evaluateVolume"));
        assertTrue(resources.contains("ResourceDescriptorHeap[NonUniformResourceIndex(index.value)]"));
        assertTrue(resources.contains("SamplerDescriptorHeap[NonUniformResourceIndex(index.value)]"));
        assertTrue(resources.contains("RaytracingAccelerationStructure accelerationStructure"));
    }

    private static String resource(String name) throws IOException {
        String path = "/caustica/shaders/api/" + name;
        try (var input = ProgramAbiTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing public shader module " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
