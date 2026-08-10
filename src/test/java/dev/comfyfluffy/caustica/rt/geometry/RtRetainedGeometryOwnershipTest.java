package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedGeometryOwnershipTest {
    @Test
    void retainedGeometryOwnersAreSourceNeutral() throws IOException {
        Path java = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica")
                .toAbsolutePath().normalize();
        List<Path> owners = List.of(java.resolve("rt/geometry/RtPackedGeometry.java"),
                java.resolve("rt/geometry/RtRetainedGeometryBuilds.java"),
                java.resolve("rt/geometry/RtRetainedGeometryScene.java"));
        for (Path owner : owners) {
            String content = Files.readString(owner);
            String lower = content.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("net.minecraft"), owner.toString());
            assertFalse(lower.contains("rt.terrain"), owner.toString());
            assertFalse(lower.matches("(?s).*\\b(terrain|section|light|material|water)\\b.*"),
                    owner.toString());
        }

        assertFalse(Files.exists(java.resolve("rt/terrain/RtSectionBuilder.java")));
        assertFalse(Files.exists(java.resolve("rt/terrain/RtSectionTable.java")));
        String producer = Files.readString(java.resolve("rt/terrain/RtTerrain.java"));
        for (String forbidden : List.of("prepareRetainedBlas", "prepareBlasCompaction",
                "recordBlasCompaction", "finishBlasCompaction", "destroyBlasCompaction",
                "createAsyncBuffer", "createUploadBuffer")) {
            assertFalse(producer.contains(forbidden), "producer still owns GPU geometry operation " + forbidden);
        }
    }

    @Test
    void retainedBlasApiHasNoSourceNamedEntryPoints() throws IOException {
        Path accel = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt",
                "accel", "RtAccel.java").toAbsolutePath().normalize();
        String content = Files.readString(accel);
        for (String sourceNamed : List.of("prepareTerrainBlas", "PreparedTerrainCompaction",
                "prepareTerrainCompaction", "recordTerrainCompaction", "finishTerrainCompaction",
                "destroyTerrainCompaction", "queryTerrainBlasSizes", "recordTerrainBlasBuild",
                "terrainGeometries", "terrainBuildRanges", "terrainGeomCount", "terrainSplit",
                "terrainTris")) {
            assertFalse(content.contains(sourceNamed), "retained BLAS path still uses " + sourceNamed);
        }
        assertTrue(content.contains("prepareRetainedBlas"));
        assertTrue(content.contains("PreparedBlasCompaction"));
    }
}
