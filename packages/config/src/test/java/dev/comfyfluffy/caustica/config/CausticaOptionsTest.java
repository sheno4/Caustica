package dev.comfyfluffy.caustica.config;

import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaOptionsTest {
    private static final ResourceId FEATURE = ResourceId.of("test", "options");
    private static final ResourceId OTHER_FEATURE = ResourceId.of("test", "other");
    private static final Option<Float> ALPHA = Option.range("alpha", 0.0f, 1.0f, 0.25f);
    private static final Option<Boolean> FLAG = Option.bool("flag", true);
    // A dotted id, as the builtin feature's "bloom.strength" is: it nests inside the feature's own TOML
    // table rather than colliding with anything, which is what makes per-extension grouping work on disk.
    private static final Option<Float> NESTED = Option.range("group.nested", 0.0f, 10.0f, 4.0f);
    // Same id as ALPHA, different declaration — what a stale or copy-pasted token looks like.
    private static final Option<Float> ALPHA_LOOKALIKE = Option.range("alpha", 0.0f, 1.0f, 0.75f);
    private static final Option<Float> UNDECLARED = Option.range("undeclared", 0.0f, 1.0f, 0.5f);

    @TempDir
    Path configDir;

    private SettingsRegistry settings() {
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(FEATURE).option(ALPHA).option(FLAG).option(NESTED).register();
        // A second feature declaring the *same* option ids, to pin that values are namespaced per feature.
        registry.feature(OTHER_FEATURE).option(ALPHA).register();
        return registry;
    }

    private CausticaOptions load() {
        return CausticaOptions.load(configDir.resolve("caustica-options.toml"), settings());
    }

    private CausticaOptions loadWithFile(String toml) throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        Files.writeString(path, toml);
        return CausticaOptions.load(path, settings());
    }

    @Test
    void anAbsentFileLeavesEveryOptionAtItsDeclaredDefault() {
        OptionValues options = load().options(FEATURE);

        assertEquals(0.25f, options.get(ALPHA));
        assertEquals(true, options.get(FLAG));
        assertEquals(4.0f, options.get(NESTED));
    }

    @Test
    void aFileValueOverridesTheDefaultAndIsClampedToTheOptionsRange() throws IOException {
        OptionValues options = loadWithFile("""
                ["test:options"]
                alpha = 0.5
                flag = false
                [ "test:options".group ]
                nested = 99.0
                """).options(FEATURE);

        assertEquals(0.5f, options.get(ALPHA));
        assertEquals(false, options.get(FLAG));
        assertEquals(10.0f, options.get(NESTED), "an out-of-range file value clamps, it does not throw");
    }

    @Test
    void oneFeaturesValueDoesNotLeakIntoAnothersSameNamedOption() throws IOException {
        CausticaOptions options = loadWithFile("""
                ["test:options"]
                alpha = 0.5
                """);

        assertEquals(0.5f, options.options(FEATURE).get(ALPHA));
        assertEquals(0.25f, options.options(OTHER_FEATURE).get(ALPHA));
    }

    @Test
    void dottedFeatureAndOptionIdsKeepTheirOwnValues() {
        ResourceId feature = ResourceId.of("test", "feature");
        ResourceId nestedFeature = ResourceId.of("test", "feature.group");
        Option<Float> grouped = Option.range("group.value", 0, 1, 0.25f);
        Option<Float> plain = Option.range("value", 0, 1, 0.75f);
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(feature).option(grouped).register();
        registry.feature(nestedFeature).option(plain).register();
        Path path = configDir.resolve("caustica.toml");
        CausticaOptions options = CausticaOptions.load(path, registry);
        var before = options.snapshot();

        options.set(feature, grouped, 0.5f);

        assertEquals(0.25f, before.options(feature).get(grouped));
        assertEquals(0.5f, options.options(feature).get(grouped));
        assertEquals(0.75f, options.options(nestedFeature).get(plain));
        assertEquals(0.75f, options.preference(nestedFeature, plain));
        CausticaOptions reloaded = CausticaOptions.load(path, registry);
        assertEquals(0.5f, reloaded.options(feature).get(grouped));
        assertEquals(0.75f, reloaded.options(nestedFeature).get(plain));
        reloaded.set(nestedFeature, plain, 0.9f);
        CausticaOptions bothSaved = CausticaOptions.load(path, registry);
        assertEquals(0.5f, bothSaved.options(feature).get(grouped));
        assertEquals(0.9f, bothSaved.options(nestedFeature).get(plain));
    }

    @Test
    void aSystemPropertyOverridesTheFile() throws IOException {
        String property = "caustica.option.test.options.alpha";
        System.setProperty(property, "0.9");
        try {
            CausticaOptions options = loadWithFile("""
                    ["test:options"]
                    alpha = 0.5
                    """);

            assertEquals(0.9f, options.options(FEATURE).get(ALPHA));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void anUnparseableSystemPropertyFallsBackToTheFile() throws IOException {
        String property = "caustica.option.test.options.alpha";
        System.setProperty(property, "not-a-number");
        try {
            CausticaOptions options = loadWithFile("""
                    ["test:options"]
                    alpha = 0.5
                    """);

            assertEquals(0.5f, options.options(FEATURE).get(ALPHA));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void aWriteIsVisibleLiveAndSurvivesAReload() {
        Path path = configDir.resolve("caustica-options.toml");
        CausticaOptions options = CausticaOptions.load(path, settings());

        options.set(FEATURE, ALPHA, 0.75);

        assertEquals(0.75f, options.options(FEATURE).get(ALPHA));
        assertEquals(0.75f, CausticaOptions.load(path, settings()).options(FEATURE).get(ALPHA),
                "a written value must round-trip through the TOML file");
    }

    @Test
    void aWriteIsClampedToTheOptionsRange() {
        CausticaOptions options = load();

        options.set(FEATURE, ALPHA, 5.0);

        assertEquals(1.0f, options.options(FEATURE).get(ALPHA));
    }

    @Test
    void aSnapshotTakenBeforeAWriteKeepsTheOldValue() {
        CausticaOptions options = load();
        var frame = options.snapshot();

        options.set(FEATURE, ALPHA, 0.75);

        assertEquals(0.25f, frame.options(FEATURE).get(ALPHA),
                "a frame holding a snapshot must not see a mid-frame write");
        assertEquals(0.75f, options.options(FEATURE).get(ALPHA),
                "the live view must see it immediately");
    }

    @Test
    void readingAnOptionTheFeatureNeverDeclaredThrows() {
        OptionValues options = load().options(FEATURE);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> options.get(UNDECLARED));
        assertTrue(e.getMessage().contains("undeclared"), e.getMessage());
    }

    @Test
    void readingALookalikeOptionWithADifferentDeclarationThrows() {
        OptionValues options = load().options(FEATURE);

        assertThrows(IllegalArgumentException.class, () -> options.get(ALPHA_LOOKALIKE),
                "a token sharing an id but not the declaration must not silently resolve");
    }

    @Test
    void applyIsVisibleImmediatelyButWritesNothingUntilSave() throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        CausticaOptions options = CausticaOptions.load(path, settings());

        options.apply(FEATURE, ALPHA, 0.75);

        assertEquals(0.75f, options.options(FEATURE).get(ALPHA), "the live view sees it at once");
        assertEquals(0.25f, CausticaOptions.load(path, settings()).options(FEATURE).get(ALPHA),
                "a dragged slider must not reach disk on every tick");

        options.save();

        assertEquals(0.75f, CausticaOptions.load(path, settings()).options(FEATURE).get(ALPHA));
        assertTrue(Files.readString(path).contains("0.75"));
    }

    @Test
    void saveWithNothingAppliedLeavesTheFileAlone() throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        Files.writeString(path, """
                ["test:options"]
                alpha = 0.5
                """);
        CausticaOptions options = CausticaOptions.load(path, settings());
        String before = Files.readString(path);

        options.save();

        assertEquals(before, Files.readString(path));
    }

    @Test
    void aSecondApplyToTheSameOptionPersistsOnlyTheLastValue() throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        CausticaOptions options = CausticaOptions.load(path, settings());

        options.apply(FEATURE, ALPHA, 0.4);
        options.apply(FEATURE, ALPHA, 0.6);
        options.save();

        assertEquals(0.6f, CausticaOptions.load(path, settings()).options(FEATURE).get(ALPHA));
    }

    @Test
    void anUnknownFeatureThrows() {
        CausticaOptions options = load();
        ResourceId unknown = ResourceId.of("test", "missing");

        assertThrows(NullPointerException.class, () -> options.options(unknown));
        assertThrows(NullPointerException.class, () -> options.set(unknown, ALPHA, 0.5));
    }

    @Test
    void processOverrideRemainsEffectiveWhilePreferenceChangesAndSaves() {
        String property = "caustica.option.test.options.alpha";
        Path path = configDir.resolve("caustica.toml");
        System.setProperty(property, "0.9");
        try {
            CausticaOptions options = CausticaOptions.load(path, settings());
            options.set(FEATURE, ALPHA, 0.6);
            assertTrue(options.overridden(FEATURE, ALPHA));
            assertEquals(0.9f, options.options(FEATURE).get(ALPHA));
            assertEquals(0.6f, options.preference(FEATURE, ALPHA));
        } finally {
            System.clearProperty(property);
        }
        assertEquals(0.6f, CausticaOptions.load(path, settings()).options(FEATURE).get(ALPHA));
    }

    @Test
    void laterExtensionRegistrationKeepsExistingEditsAndSnapshots() throws IOException {
        Path path = configDir.resolve("caustica.toml");
        Files.writeString(path, "[\"test:extension\"]\ncount=7\n");
        CausticaOptions options = CausticaOptions.load(path, settings());
        options.apply(FEATURE, ALPHA, 0.6);
        var before = options.snapshot();
        ResourceId extension = ResourceId.of("test", "extension");
        Option<Integer> count = Option.integer("count", 0, 10, 2);
        SettingsRegistry additions = new SettingsRegistry();
        additions.feature(extension).option(count).register();
        options.register(additions);
        options.register(additions);
        assertEquals(7, options.options(extension).get(count));
        assertEquals(0.6f, options.options(FEATURE).get(ALPHA));
        assertEquals(0.6f, before.options(FEATURE).get(ALPHA));
        assertThrows(NullPointerException.class, () -> before.options(extension));
    }

    private enum Quality { LOW, HIGH }

    @Test
    void allTypesRoundTripThroughExplicitStorageKeys() {
        Option<Integer> count = Option.integer("count", 0, 10, 2).storage("render.count", "test.render.count");
        Option<Integer> selection = Option.intChoice("selection", 0, List.of(0, 2));
        Option<String> mode = Option.stringChoice("mode", "raw", List.of("raw", "nrd"));
        Option<Quality> quality = Option.enumOf("quality", Quality.LOW, List.of(Quality.values()));
        Option<Integer> color = Option.color("color", 0);
        Option<Optional<String>> asset = Option.optionalString("asset");
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(FEATURE).option(count).option(selection).option(mode).option(quality)
                .option(color).option(asset).register();
        Path path = configDir.resolve("caustica.toml");
        CausticaOptions options = CausticaOptions.load(path, registry);
        options.apply(FEATURE, count, 99);
        options.apply(FEATURE, selection, 2);
        options.apply(FEATURE, mode, "nrd");
        options.apply(FEATURE, quality, Quality.HIGH);
        options.apply(FEATURE, color, 0x12abef);
        options.set(FEATURE, asset, Optional.of("scene.gltf"));
        CausticaOptions reloaded = CausticaOptions.load(path, registry);
        assertEquals(10, reloaded.options(FEATURE).get(count));
        assertEquals(2, reloaded.options(FEATURE).get(selection));
        assertEquals("nrd", reloaded.options(FEATURE).get(mode));
        assertEquals(Quality.HIGH, reloaded.options(FEATURE).get(quality));
        assertEquals(0x12abef, reloaded.options(FEATURE).get(color));
        assertEquals(Optional.of("scene.gltf"), reloaded.options(FEATURE).get(asset));
        reloaded.set(FEATURE, asset, Optional.empty());
        assertEquals(Optional.empty(), CausticaOptions.load(path, registry).options(FEATURE).get(asset));
    }

    @Test
    void legacyImportPreservesCanonicalPreferencesAndUnknownKeys() throws IOException {
        Path path = configDir.resolve("caustica.toml");
        Path legacy = configDir.resolve("caustica-options.toml");
        Files.writeString(path, "[\"test:options\"]\nalpha=0.8\nunknown=42\n");
        Files.writeString(legacy, "[\"test:options\"]\nalpha=0.1\nflag=false\n");
        CausticaOptions options = CausticaOptions.load(path, settings());
        options.importLegacy(legacy);
        assertEquals(0.8f, options.options(FEATURE).get(ALPHA));
        assertEquals(false, options.options(FEATURE).get(FLAG));
        try (var parsed = com.electronwill.nightconfig.core.file.CommentedFileConfig.of(path)) {
            parsed.load();
            assertEquals(42, parsed.<Integer>get("test:options.unknown"));
        }
    }
}
