package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApiShowcaseExtensionTest {
    @Test
    void declaresTheOptionTokenUsedByItsSessionPass() {
        SettingsRegistry registry = new SettingsRegistry();

        new ApiShowcaseExtension().registerSettings(registry);

        assertTrue(registry.declared(ApiShowcaseExtension.ID));
        assertSame(ApiShowcaseExtension.COLOUR_GRADE_STRENGTH,
                registry.settings(ApiShowcaseExtension.ID).option("colour_grade_strength"));
    }
}
