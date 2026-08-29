package dev.comfyfluffy.caustica.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettingsRegistryTest {
    private static final ResourceId FEATURE = ResourceId.of("test", "feature");

    @Test
    void declaresOptionsAgainstAFeatureIdTheEngineNeverSees() {
        SettingsRegistry registry = new SettingsRegistry();
        Option<Boolean> enabled = Option.bool("enabled", true);

        FeatureSettings settings = registry.feature(FEATURE)
                .title(DisplayText.translatable("caustica.feature.test"))
                .category(FeatureCategory.POST_PROCESSING)
                .option(enabled)
                .register();

        assertSame(settings, registry.settings(FEATURE));
        assertTrue(registry.declared(FEATURE));
        assertEquals(List.of(enabled), settings.options());
        assertEquals(FeatureCategory.POST_PROCESSING, settings.category());
    }

    @Test
    void titleFallsBackToTheFeatureId() {
        SettingsRegistry registry = new SettingsRegistry();

        FeatureSettings settings = registry.feature(FEATURE).register();

        assertEquals(DisplayText.literal(FEATURE.toString()), settings.title());
    }

    @Test
    void rejectsDuplicateRegistrationAndUnknownLookup() {
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(FEATURE).register();

        assertThrows(IllegalStateException.class, () -> registry.feature(FEATURE).register());
        assertThrows(IllegalArgumentException.class,
                () -> registry.settings(ResourceId.of("test", "absent")));
    }

    @Test
    void rejectsAnOptionInAnUndeclaredGroup() {
        SettingsRegistry registry = new SettingsRegistry();
        SettingsBuilder builder = registry.feature(FEATURE)
                .option(Option.bool("enabled", true).inGroup("advanced"));

        assertThrows(IllegalArgumentException.class, builder::register);
    }

    @Test
    void acceptsAnOptionKindNoEngineRegistryGetsToVeto() {
        SettingsRegistry registry = new SettingsRegistry();

        FeatureSettings settings = registry.feature(FEATURE)
                .option(Option.color("tint", 0xFF8800))
                .register();

        assertEquals(Option.Kind.COLOR, settings.option("tint").kind());
    }
}
