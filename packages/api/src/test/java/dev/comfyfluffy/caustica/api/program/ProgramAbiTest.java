package dev.comfyfluffy.caustica.api.program;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(types.contains("NormalTransform previousNormalToScene"));
        assertTrue(types.contains("float3 previousGeometricNormal"));
        assertTrue(types.contains("ShaderRootData shaderRoot"));
        assertTrue(types.contains("uint64_t implementationData"));
        assertTrue(types.contains("uint64_t bindingData"));
        assertTrue(types.contains("public struct VolumeInput"));
        assertTrue(types.contains("public struct VolumeProperties"));
        assertTrue(!types.contains("materialData"));
        assertTrue(!types.contains("Medium"));
        assertTrue(types.contains("bool geometry_thin_walled = false"));
        assertTrue(coverage.contains("float2 barycentrics"));
        assertTrue(coverage.contains("float evaluateCoverage"));
        assertTrue(!coverage.contains("float4 evaluateCoverage"));
        assertTrue(coverage.contains("uint64_t implementationData"));
        assertTrue(coverage.contains("uint64_t bindingData"));
        assertTrue(!coverage.contains("COVERAGE_MODE_OPAQUE"));
        assertTrue(!coverage.contains("uint flags"));
        assertTrue(!coverage.contains("geometrySemantics"));
        assertTrue(composition.contains("associatedtype Environments : IEnvironmentDispatch"));
        assertTrue(composition.contains("associatedtype Volumes : IVolumeDispatch"));
        assertTrue(!composition.contains("SurfaceModifiers"));
        assertTrue(!surface.contains("evaluateMedium"));
        assertTrue(volume.contains("public interface IVolumeModel"));
        assertTrue(volume.contains("VolumeProperties evaluateVolume"));
        assertTrue(volume.contains("evaluateBoundaryLighting"));
        assertTrue(types.contains("public struct VolumeBoundaryLightingInput"));
        assertTrue(!volume.contains("evaluateVolumeLighting"));
        assertTrue(volume.contains("volumeAbsorptionFromTransmittance"));
        assertTrue(types.contains("float3 absorptionCoefficient = float3(0.0)"));
        assertTrue(types.contains("float indexOfRefraction = 1.0"));
        String volumeProperties = structBody(types, "VolumeProperties");
        assertEquals(2L, volumeProperties.lines()
                .filter(line -> line.stripLeading().startsWith("public "))
                .count());
        assertTrue(!types.contains("guideTransmittance"));
        assertTrue(!types.contains("boundaryRayBias"));
        assertTrue(!types.contains("VOLUME_TERMINATE_ON_MISS"));
        assertTrue(!types.contains("VOLUME_BOUNDARY_LIGHTING"));
        assertTrue(!surface.contains("evaluateResponse"));
        assertTrue(!types.contains("SurfaceClosure"));
        assertTrue(!types.contains("BsdfQuery"));
        assertTrue(resources.contains("ResourceDescriptorHeap[NonUniformResourceIndex(index.value)]"));
        assertTrue(resources.contains("SamplerDescriptorHeap[NonUniformResourceIndex(index.value)]"));
        assertTrue(resources.contains("public struct AccelerationStructureIndex"));
        assertTrue(!resources.contains("RaytracingAccelerationStructure accelerationStructure"));
        assertEquals(Set.of("compositionData", "implementationData", "bindingData", "instanceData"),
                uint64FieldNames(types));
        assertEquals(Set.of("implementationData", "bindingData", "instanceData"),
                uint64FieldNames(coverage));
    }

    private static String structBody(String source, String name) {
        String declaration = "public struct " + name + " {";
        int start = source.indexOf(declaration);
        if (start < 0) throw new AssertionError("missing struct " + name);
        start += declaration.length();
        int end = source.indexOf("};", start);
        if (end < 0) throw new AssertionError("unterminated struct " + name);
        return source.substring(start, end);
    }

    private static Set<String> uint64FieldNames(String source) {
        Matcher fields = Pattern.compile("public\\s+uint64_t\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;")
                .matcher(source);
        Set<String> names = new HashSet<>();
        while (fields.find()) names.add(fields.group(1));
        return Set.copyOf(names);
    }

    private static String resource(String name) throws IOException {
        String path = "/caustica/shaders/api/" + name;
        try (var input = ProgramAbiTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing public shader module " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
