package dev.comfyfluffy.caustica.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtTerrainGeometryOwnershipTest {
    private static final Path TERRAIN = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
            "minecraft", "terrain", "RtTerrain.java").toAbsolutePath().normalize();

    @Test
    void terrainDoesNotOwnRetainedGpuGeometry() throws IOException {
        String source = Files.readString(TERRAIN);
        for (String forbidden : List.of(
                "RtRetainedGeometryScene",
                "RtRetainedGeometryBuilds",
                "RtRetainedGeometryCoordinator",
                "acquirePackedCoordinator",
                "releasePackedCoordinator",
                "Prepared<",
                "Completion<",
                "Resident<",
                "RtGpuExecutor",
                "GpuBuffer",
                "RtAccel.prepare",
                "RtAccel.refit",
                "RtGeometryAbi.writeRecord",
                "markPublished(",
                "retireUnpublished(",
                "geometry.prepare(",
                "geometry.publish(",
                "geometry.ensureEmpty(",
                "geometry.destroy(",
                "geometry.clearAsync(",
                "geometry.publishBatch(")) {
            assertFalse(source.contains(forbidden), "terrain retains GPU geometry ownership through " + forbidden);
        }
    }
}
