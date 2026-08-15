package dev.comfyfluffy.caustica.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtEntitiesSceneTableOwnershipTest {
    private static final Path ENTITIES = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
            "minecraft", "entity", "RtEntities.java").toAbsolutePath().normalize();

    @Test
    void entitiesSubmitIntoTheManagerOwnedSceneTable() throws IOException {
        String source = Files.readString(ENTITIES);
        for (String forbidden : List.of(
                "TableSlot",
                "tableRing",
                "geometryTableAddress",
                "RtGeometryAbi.writeRecord",
                "build.instances.add",
                "GpuBuffer",
                "createBuffer",
                "MotionArena",
                "EntitySlot",
                "RtSceneGeometryManager.FrameUpdate",
                "RtSceneGeometryManager",
                "PackedInput",
                "RetainedPayload",
                "RtPackedGeometry",
                "RtAccel.OpacityMicromap",
                "appendRigidReuse",
                "fitYawTransform",
                "shadeHash",
                "RtAccel.prepare",
                "RtAccel.refit",
                "RtAccel.destroyCallerOwnedAccel",
                "retireAfterGraphics")) {
            assertFalse(source.contains(forbidden), "entity capture still owns scene-table state through " + forbidden);
        }
    }
}
