package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.provider.EmissionFootprint;
import dev.comfyfluffy.caustica.api.provider.MaterialAnalysis;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MaterialEpochSnapshotTest {
    @Test
    void exposesPublicEmissionSemanticsWithoutRendererBindingIds() {
        EmissionFootprint footprint = new EmissionFootprint(1, new float[]{0.5f, 0.25f, 0.125f, 1.0f});
        RtMaterialDesc material = material(RtMaterialDesc.EmissionSource.DERIVED_MASK, 42.0f);
        MaterialEpochSnapshot snapshot = new MaterialEpochSnapshot(7L, Map.of(), new int[0], 1,
                Map.of(), Map.of(), 0, List.of(material), List.of(footprint),
                RtMaterialRegistry.CompiledOverrideLookup.of(List.of()), new int[]{0}, new byte[]{0});
        MaterialAnalysis analysis = snapshot.analyze(new SceneMesh.FallbackMaterial(null));

        assertEquals(MaterialAnalysis.EmissionSource.DERIVED_MASK, analysis.emissionSource());
        assertEquals(42.0f, analysis.emissionLuminanceCdM2());
        assertSame(footprint, analysis.emissionFootprint());
        assertSame(analysis, snapshot.analyze(new SceneMesh.FallbackMaterial(null)));
    }

    @Test
    void mapsEveryInternalEmissionSourceToTheHostVocabulary() {
        for (RtMaterialDesc.EmissionSource source : RtMaterialDesc.EmissionSource.values()) {
            MaterialEpochSnapshot snapshot = new MaterialEpochSnapshot(1L, Map.of(), new int[0], 1,
                    Map.of(), Map.of(), 0,
                    List.of(material(source, source == RtMaterialDesc.EmissionSource.NONE ? 0.0f : 1.0f)),
                    java.util.Collections.singletonList(null),
                    RtMaterialRegistry.CompiledOverrideLookup.of(List.of()), new int[]{0}, new byte[]{0});

            assertEquals(MaterialAnalysis.EmissionSource.valueOf(source.name()),
                    snapshot.analyze(new SceneMesh.FallbackMaterial(null)).emissionSource());
        }
    }

    @Test
    void unknownNamedMaterialsNeverFallBackSilently() {
        MaterialEpochSnapshot snapshot = new MaterialEpochSnapshot(1L, Map.of(), new int[0], 1,
                Map.of(), Map.of(), 0, List.of(material(RtMaterialDesc.EmissionSource.NONE, 0.0f)),
                java.util.Collections.singletonList(null),
                RtMaterialRegistry.CompiledOverrideLookup.of(List.of()), new int[]{0}, new byte[]{0});

        assertThrows(IllegalArgumentException.class, () -> snapshot.analyze(
                new SceneMesh.NamedMaterial(dev.comfyfluffy.caustica.api.provider.MaterialHandle.of(
                        "test", "missing"))));
    }

    private static RtMaterialDesc material(RtMaterialDesc.EmissionSource emissionSource, float luminance) {
        return new RtMaterialDesc(0, RtMaterialDesc.Source.NEUTRAL, 0,
                0.5f, 0.0f, 1.5f, 0.0f, emissionSource, luminance,
                RtMaterialDesc.EmissionSummary.NONE, 0);
    }
}
