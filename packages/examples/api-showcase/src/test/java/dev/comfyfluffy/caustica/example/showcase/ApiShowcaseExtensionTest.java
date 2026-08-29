package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApiShowcaseExtensionTest {
    @Test
    void registersOneMinecraftWorldSessionFactory() {
        AtomicReference<MinecraftWorldSessionFactory> factory = new AtomicReference<>();
        var api = new MinecraftApi(accepted -> {
            factory.set(accepted);
            return () -> { };
        }, id -> { throw new AssertionError(id); });

        ApiShowcaseExtension extension = new ApiShowcaseExtension();
        extension.registerMinecraft(api);

        assertInstanceOf(MinecraftExtension.class, extension);
        assertNotNull(factory.get());
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
