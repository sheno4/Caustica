package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NrdSignalAggregationContractTest {
    @Test
    void missesAttenuateThroughTheRayRangeWithoutBecomingFiniteHitDistance() throws Exception {
        String indirect = shader("retained_indirect.slang");
        assertTrue(indirect.contains("attenuationTravel = missed ? ray.TMax"));
        assertTrue(indirect.contains("float signalHitDistance = missed\n                    ? 0.0"));
        assertFalse(indirect.contains("state.signalHitDistance + max(travel"));
    }

    @Test
    void diffuseWeightsHitDistanceAfterMatchingDemodulation() throws Exception {
        String indirect = shader("retained_indirect.slang");
        String signals = shader("nrd_signals.slang");
        String lights = shader("retained_lights.slang");
        String closest = shader("closest_hit.slang");
        assertTrue(indirect.contains("nrdLuminance(\n                        nrdDemodulate(contribution, diffuseBsdfEstimate))"));
        assertTrue(indirect.contains("diffuseHitDistanceSum += contributionWeight * signalHitDistance"));
        assertTrue(signals.contains("min(directFraction, 0.5)"));
        assertTrue(lights.contains("sample.signalDistance = distance"));
        assertTrue(closest.contains("directHitDistance = selected.signalDistance"));
        assertFalse(closest.contains("directHitDistance = selected.maximumDistance"));
    }

    @Test
    void specularUsesNearestNonzeroDistanceAcrossTraceAndPrimaryMerge() throws Exception {
        String indirect = shader("retained_indirect.slang");
        String primary = shader("primary_rgen.slang");
        String signals = shader("nrd_signals.slang");
        assertTrue(primary.contains("primary.diffuseHitDistance, 0.0, albedo, specularAlbedo"));
        assertTrue(indirect.contains("min(specularHitDistance, signalHitDistance)"));
        assertTrue(signals.contains("nearestNonzeroHitDistance"));
        assertTrue(signals.contains("hitDistance <= 0.0 ? previousHitDistance"));
        assertTrue(signals.contains("min(previousHitDistance, hitDistance)"));
        assertTrue(signals.contains("viewZ, 1.0, false"));
        assertTrue(signals.contains("viewZ, roughness, true"));
    }

    private static String shader(String name) throws IOException {
        try (var input = NrdSignalAggregationContractTest.class.getResourceAsStream(
                "/caustica/shaders/world/" + name)) {
            if (input == null) throw new IOException("missing shader " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
