package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CausticaConfigTest {
    @Test
    void loadsTheSavedFoveationAngleWhenOnlyTheDetailIndexExists() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("composite.foveation-detail", 8);
        CausticaConfig.migrateFoveationCoverage(config);
        assertEquals(25, config.<Integer>get("composite.foveation-coverage-degrees").intValue());

        config.set("composite.foveation-coverage-degrees", 37);
        CausticaConfig.migrateFoveationCoverage(config);
        assertEquals(37, config.<Integer>get("composite.foveation-coverage-degrees").intValue());
    }

    @Test
    void migratesLegacyFixedFoveationToggleToMode() {
        CommentedConfig legacyTrue = CommentedConfig.inMemory();
        legacyTrue.set("composite.fixed-foveation", true);
        CausticaConfig.migrateFoveationMode(legacyTrue);
        assertEquals("fixed", legacyTrue.<String>get("composite.foveation-mode"));

        CommentedConfig legacyFalse = CommentedConfig.inMemory();
        legacyFalse.set("composite.fixed-foveation", false);
        CausticaConfig.migrateFoveationMode(legacyFalse);
        assertEquals("off", legacyFalse.<String>get("composite.foveation-mode"));

        CommentedConfig explicit = CommentedConfig.inMemory();
        explicit.set("composite.fixed-foveation", true);
        explicit.set("composite.foveation-mode", "papertracker");
        CausticaConfig.migrateFoveationMode(explicit);
        assertEquals("papertracker", explicit.<String>get("composite.foveation-mode"));
    }

    @Test
    void invalidPeakNitsFallsBackToDefault() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        int previous = setting.value();
        try {
            setting.set(2000);
            assertEquals(2000, setting.value());

            setting.set(900);
            assertEquals(1000, setting.value());
        } finally {
            setting.set(previous);
        }
    }
}
