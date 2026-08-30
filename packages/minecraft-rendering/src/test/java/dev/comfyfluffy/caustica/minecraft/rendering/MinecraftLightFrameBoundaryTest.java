package dev.comfyfluffy.caustica.minecraft.rendering;

import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class MinecraftLightFrameBoundaryTest {
    @Test
    void publicFrameComponentsDoNotExposeEngineImplementationTypes() {
        for (var component : MinecraftLightFrame.class.getRecordComponents()) {
            assertFalse(component.getType().getPackageName().startsWith("dev.comfyfluffy.caustica.engine"),
                    component.getName());
        }
        assertEquals(MinecraftTerrainLightSnapshot.class,
                MinecraftLightFrame.class.getRecordComponents()[2].getType());
    }

    @Test
    void terrainSnapshotPreservesIdentityGenerationAndCopiesItsBatchList() {
        MinecraftTerrainLightBatch batch = new MinecraftTerrainLightBatch(7L, 11L, List.of());
        List<MinecraftTerrainLightBatch> source = new java.util.ArrayList<>(List.of(batch));
        MinecraftTerrainLightSnapshot snapshot = new MinecraftTerrainLightSnapshot(source, 13L);
        source.clear();

        assertEquals(List.of(batch), snapshot.batches());
        assertNotSame(source, snapshot.batches());
        assertEquals(13L, snapshot.generation());
        assertEquals(7L, snapshot.batches().getFirst().sectionKey());
        assertEquals(11L, snapshot.batches().getFirst().revision());
    }
}
