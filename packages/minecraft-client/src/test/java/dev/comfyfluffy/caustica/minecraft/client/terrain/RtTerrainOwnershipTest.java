package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class RtTerrainOwnershipTest {
    @Test
    void terrainAndWorkersAreIndependentRootOwnedInstances() {
        RtWorkerPool firstWorkers = new RtWorkerPool(1);
        RtWorkerPool secondWorkers = new RtWorkerPool(1);
        RtTerrain firstTerrain = new RtTerrain(firstWorkers, MinecraftTelemetry.disabled());
        RtTerrain secondTerrain = new RtTerrain(secondWorkers, MinecraftTelemetry.disabled());

        assertNotSame(firstWorkers, secondWorkers);
        assertNotSame(firstTerrain, secondTerrain);
        assertFalse(hasStaticFieldOfType(RtTerrain.class, RtTerrain.class));
        assertFalse(hasStaticFieldOfType(RtWorkerPool.class, RtWorkerPool.class));
    }

    private static boolean hasStaticFieldOfType(Class<?> owner, Class<?> fieldType) {
        return Arrays.stream(owner.getDeclaredFields())
                .anyMatch(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == fieldType);
    }
}
