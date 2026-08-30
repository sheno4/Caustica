package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApiShowcaseExtensionTest {
    @Test
    void registersOrderedSelectionOwnerAndGeometryConsumerFactories() {
        List<MinecraftWorldSessionFactory> factories = new ArrayList<>();
        var api = new MinecraftApi(accepted -> {
            factories.add(accepted);
            return () -> { };
        }, id -> { throw new AssertionError(id); });

        ApiShowcaseExtension extension = new ApiShowcaseExtension();
        extension.registerMinecraft(api);

        assertInstanceOf(MinecraftExtension.class, extension);
        assertEquals(2, factories.size());
        assertNotNull(factories.get(0));
        assertNotNull(factories.get(1));
    }

    @Test
    void declaresTheOptionTokenUsedByItsSessionPass() {
        SettingsRegistry registry = new SettingsRegistry();

        new ApiShowcaseExtension().registerSettings(registry);

        assertTrue(registry.declared(ApiShowcaseExtension.ID));
        assertSame(ApiShowcaseExtension.COLOUR_GRADE_STRENGTH,
                registry.settings(ApiShowcaseExtension.ID).option("colour_grade_strength"));
    }
}
