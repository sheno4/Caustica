package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaConfigTest {
    @Test
    void entityCaptureAndTextureInternalsAreNotConfigurationSettings() {
        CausticaConfig.ensureRegistered();
        Set<String> paths = CausticaConfig.settings().stream()
                .map(CausticaConfig.RuntimeSetting::tomlPath)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(paths.contains("entities.debug.capture-parity"));
        assertTrue(paths.stream().noneMatch(path -> path.startsWith("entities.textures.")));
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

    /**
     * A holder missing from {@code HOLDERS} never class-initializes, so its settings silently vanish from
     * both the config file and the settings screen. That is invisible at runtime, so it fails here instead.
     */
    @Test
    void everySettingsHolderIsListedForInitialization() {
        List<Class<?>> found = new ArrayList<>();
        collectHolders(CausticaConfig.class, found);

        assertFalse(found.isEmpty(), "the reflection walk found no holders at all");
        List<Class<?>> missing = found.stream().filter(holder -> !CausticaConfig.HOLDERS.contains(holder)).toList();
        assertTrue(missing.isEmpty(), "holders missing from CausticaConfig.HOLDERS: " + missing);
        assertEquals(found.size(), CausticaConfig.HOLDERS.size(),
                "HOLDERS lists something that is not a settings holder");
    }

    private static void collectHolders(Class<?> owner, List<Class<?>> found) {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.isInterface() || !Modifier.isStatic(nested.getModifiers())) {
                continue;
            }
            // The setting implementations live alongside the holders but declare no settings of their own.
            if (CausticaConfig.RuntimeSetting.class.isAssignableFrom(nested)) {
                continue;
            }
            found.add(nested);
            collectHolders(nested, found);
        }
    }

    @Test
    void everySettingWithAGroupIsReadableAsAScreenRow() {
        CausticaConfig.ensureRegistered();

        List<CausticaConfig.RuntimeSetting<?>> rows = CausticaConfig.settings().stream()
                .filter(setting -> setting.group() != null)
                .toList();

        assertFalse(rows.isEmpty(), "the engine screen has no rows to show");
        for (CausticaConfig.RuntimeSetting<?> setting : rows) {
            assertEquals("caustica.setting." + setting.tomlPath(), setting.translationKey());
            assertNotNull(setting.defaultValue(), setting.tomlPath() + " has no default to reset to");
        }
    }

    /** The clamp a screen slider spans and the clamp a write applies have to be the same numbers. */
    @Test
    void intBoundsMatchWhatSetActuallyClamps() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        int previous = setting.value();
        try {
            setting.set(setting.maximum() + 1);
            assertEquals(setting.maximum(), setting.value());

            setting.set(setting.minimum() - 1);
            assertEquals(setting.minimum(), setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void floatBoundsMatchWhatSetActuallyClamps() {
        CausticaConfig.FloatSetting setting = CausticaConfig.Rt.Tonemap.GAMMA;
        float previous = setting.value();
        try {
            setting.set(setting.maximum() + 1.0f);
            assertEquals(setting.maximum(), setting.value());

            setting.set(setting.minimum() - 1.0f);
            assertEquals(setting.minimum(), setting.value());

            setting.set(Float.NaN);
            assertEquals(setting.defaultValue(), setting.value(), "a non-finite value falls back, not clamps");
        } finally {
            setting.set(previous);
        }
    }

    /**
     * A slider span narrower than the clamp is the point of {@code sliderRange}: it keeps a deliberately
     * large configured value legal instead of truncating it the first time the screen opens.
     */
    @Test
    void aNarrowedSliderSpanDoesNotNarrowTheStoredClamp() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        int previous = setting.value();
        try {
            assertTrue(setting.sliderMaximum() < setting.maximum());

            setting.set(setting.sliderMaximum() + 8);
            assertEquals(setting.sliderMaximum() + 8, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    /** The engine and extension stores must name this concept identically, or the one form renderer over
     *  both has to translate vocabulary — which is where the two would drift apart. */
    @Test
    void bothStoresExposeTheSameSliderVocabulary() {
        CausticaConfig.IntSetting engineInt = CausticaConfig.Rt.Composite.SPP;
        CausticaConfig.FloatSetting engineFloat = CausticaConfig.Rt.Tonemap.GAMMA;
        var extensionOption = dev.comfyfluffy.caustica.builtin.BloomPass.THRESHOLD_SCENE_LINEAR;

        assertEquals(1, engineInt.sliderMinimum());
        assertEquals(8, engineInt.sliderMaximum());
        assertEquals(0.5f, engineFloat.sliderMinimum());
        assertEquals(1.5f, engineFloat.sliderMaximum());
        assertEquals(0.0, extensionOption.sliderMinimum());
        assertEquals(16.0, extensionOption.sliderMaximum());
        assertEquals(65504.0, extensionOption.maximum(), "the slider span is not the storage clamp");
    }

    @Test
    void aChoiceSettingReportsItsChoicesAndRejectsAnythingElse() {
        CausticaConfig.StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        String previous = setting.get();
        try {
            assertEquals(List.of("auto", "manual"), setting.choices());

            setting.set("MANUAL");
            assertEquals("manual", setting.get(), "matching is case-insensitive, storage is canonical");

            setting.set("sideways");
            assertEquals("auto", setting.get());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void slotBindingsPersistAsOptionalStringsWithNoScreenRow() {
        assertNull(CausticaConfig.Rt.Composition.SKY.group());
        assertEquals("composition.slots.sky", CausticaConfig.Rt.Composition.SKY.tomlPath());
    }
}
