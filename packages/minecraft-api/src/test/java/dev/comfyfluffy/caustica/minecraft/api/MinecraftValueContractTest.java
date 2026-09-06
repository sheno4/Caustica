package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MinecraftValueContractTest {
    @Test
    void environmentSelectionPublishesDirectly() throws ReflectiveOperationException {
        assertEquals(void.class, MinecraftEnvironmentSelector.class
                .getMethod("select", EnvironmentBinding.class).getReturnType());
    }

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

    @Test
    void processApiExposesSessionRegistration() {
        MinecraftWorldSessionChannel sessions = factory -> () -> { };
        MinecraftApi api = new MinecraftApi(sessions);
        assertSame(sessions, api.sessions());
        assertThrows(NullPointerException.class, () -> new MinecraftApi(null));
    }
}
