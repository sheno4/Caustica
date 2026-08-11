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
        String producer = Files.readString(java.resolve("minecraft/terrain/RtTerrain.java"));
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

    @Test
    void callerOwnedClassifiedBlasApiHasNoSourceNamedEntryPoints() throws IOException {
        Path accel = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt",
                "accel", "RtAccel.java").toAbsolutePath().normalize();
        String content = Files.readString(accel);
        String lower = content.toLowerCase(Locale.ROOT);
        for (String sourceWord : List.of("entity", "terrain", "section", "minecraft", "vanilla")) {
            assertFalse(lower.matches("(?s).*\\b" + sourceWord + "\\b.*"),
                    "caller-owned BLAS path still contains source word " + sourceWord);
        }
        for (String sourceNamed : List.of("prepareEntityBlas", "preparePersistentEntityBlasBuild",
                "prepareUpdatableEntityBlasBuild", "refitEntityUpdate", "releaseEntityBlas",
                "destroyEntityAccel", "entitySplit", "entityTris", "requireEntityClasses",
                "entityGeometries", "entityBuildRanges", "queryEntityBlasSizes",
                "recordEntityBlasBuild", "PreparedBlas.entity", "_PLAN.md")) {
            assertFalse(content.contains(sourceNamed), "caller-owned BLAS path still uses " + sourceNamed);
        }
        for (String neutralName : List.of("prepareTransientBlas", "preparePersistentBlasBuild",
                "prepareUpdatableBlasBuild", "refitUpdate", "releaseTransientBlas",
                "destroyCallerOwnedAccel", "externalClassSplit", "externalClassTriangles",
                "classifiedGeometries", "classifiedBuildRanges", "queryClassifiedBlasSizes",
                "recordClassifiedBlasBuild")) {
            assertTrue(content.contains(neutralName), "caller-owned BLAS path is missing " + neutralName);
        }
    }
}
