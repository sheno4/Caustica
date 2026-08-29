package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtAccelCompactionContractTest {
    @Test
    void compactCopyCarriesFinalResidentOwnershipWithoutBuildScratch() {
        List<String> components = Arrays.stream(RtAccel.PreparedBlasCompaction.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertEquals(List.of("source", "compactedAccel", "compactedBacking"), components);
        assertFalse(components.contains("scratch"));
    }

    @Test
    void compactableBuildKeepsSourceOwnershipExplicitUntilTheCopyCompletes() {
        List<String> components = Arrays.stream(RtAccel.CompactableBuild.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertEquals(List.of("op", "accel", "backing", "scratch"), components);
    }
}
