package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftValueContractTest {
    @Test
    void dimensionKeyIsASettingsArtifactResourceIdWithoutMinecraftClasses() {
        MinecraftDimensionKey key = MinecraftDimensionKey.of("minecraft", "the_nether");
        assertEquals(ResourceId.of("minecraft", "the_nether"), key.id());
    }

    @Test
    void resourcePackGenerationCannotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackEpoch(-1));
        assertEquals(0, new ResourcePackEpoch(0).generation());
    }
}
