package dev.comfyfluffy.caustica.renderer.raytracing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraceNrdResourceContractTest {
    @Test
    void traceImagesExposeCompleteFullResolutionNrdExchange() {
        Set<String> components = Arrays.stream(TraceImages.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertTrue(components.containsAll(Set.of(
                "diffuseRadianceHitDistance",
                "specularRadianceHitDistance",
                "nrdViewZ",
                "denoisedDiffuseRadianceHitDistance",
                "denoisedSpecularRadianceHitDistance",
                "nrdStableRadiance")));
        assertEquals(15, components.size());
    }
}
