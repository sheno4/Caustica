package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.testing.InMemorySettings;
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
    void processApiRetainsTheExplicitWritableSettings() {
        MinecraftWorldSessionChannel sessions = factory -> () -> { };
        InMemorySettings options = new InMemorySettings();
        MinecraftApi api = new MinecraftApi(sessions, options);

        assertSame(sessions, api.sessions());
        assertSame(options, api.options());
        assertThrows(NullPointerException.class, () -> new MinecraftApi(null, options));
        assertThrows(NullPointerException.class, () -> new MinecraftApi(sessions, null));
        Option<Boolean> enabled = Option.bool("enabled", true);
        ResourceId feature = ResourceId.of("test", "minecraft");
        api.options().apply(feature, enabled, false);
        api.options().save();
        assertEquals(false, options.options(feature).get(enabled));
        assertEquals(1, options.saves());
    }
}
