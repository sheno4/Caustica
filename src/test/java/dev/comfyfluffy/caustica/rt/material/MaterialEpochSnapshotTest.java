package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.spi.host.MaterialEpochView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MaterialEpochSnapshotTest {
    @Test
    void exposesOnlyHostEmissionSemanticsWithoutAllocatingAnAggregate() {
        EmissionFootprint footprint = new EmissionFootprint(1, new float[]{0.5f, 0.25f, 0.125f, 1.0f});
        RtMaterialDesc material = material(RtMaterialDesc.EmissionSource.DERIVED_MASK, 42.0f);
        MaterialEpochSnapshot snapshot = new MaterialEpochSnapshot(7L, Map.of(), new int[0], 12.0f, 1,
                Map.of(), List.of(material), List.of(footprint),
                RtMaterialRegistry.CompiledOverrideLookup.of(List.of()), new int[]{0}, new byte[]{0});

        assertEquals(MaterialEpochView.EmissionSource.DERIVED_MASK, snapshot.emissionSource(0));
        assertEquals(42.0f, snapshot.emissionLuminanceCdM2(0));
        assertSame(footprint, snapshot.emissionFootprint(0));
    }

    @Test
    void mapsEveryInternalEmissionSourceToTheHostVocabulary() {
        for (RtMaterialDesc.EmissionSource source : RtMaterialDesc.EmissionSource.values()) {
            MaterialEpochSnapshot snapshot = new MaterialEpochSnapshot(1L, Map.of(), new int[0], 1.0f, 1,
                    Map.of(), List.of(material(source, source == RtMaterialDesc.EmissionSource.NONE ? 0.0f : 1.0f)),
                    java.util.Collections.singletonList(null),
                    RtMaterialRegistry.CompiledOverrideLookup.of(List.of()), new int[]{0}, new byte[]{0});

            assertEquals(MaterialEpochView.EmissionSource.valueOf(source.name()), snapshot.emissionSource(0));
        }
    }

    private static RtMaterialDesc material(RtMaterialDesc.EmissionSource emissionSource, float luminance) {
        return new RtMaterialDesc(0, RtMaterialDesc.Source.NEUTRAL, 0,
                0.5f, 0.0f, 1.5f, 0.0f, emissionSource, luminance,
                RtMaterialDesc.EmissionSummary.NONE, 0);
    }
}
