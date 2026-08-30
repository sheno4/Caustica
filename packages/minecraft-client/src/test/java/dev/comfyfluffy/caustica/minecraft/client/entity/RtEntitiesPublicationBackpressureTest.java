package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.TestProjectRoot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtEntitiesPublicationBackpressureTest {
    @Test
    void invisibleMeshPublicationDoesNotGateTheNextEntityCapture() throws Exception {
        String source = Files.readString(TestProjectRoot.resolve(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/client/entity/RtEntities.java"));

        assertFalse(source.contains("submittedPublication"));
        assertFalse(source.contains("publicationPending"));
    }
}
