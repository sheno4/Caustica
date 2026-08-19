package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.OptionValues;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    private Map<ResourceId, Feature> features() {
        CausticaRegistry registry = dev.comfyfluffy.caustica.TestRegistries.withBuiltins();
        registry.feature(FEATURE).option(ALPHA).option(FLAG).option(NESTED).register();
        // A second feature declaring the *same* option ids, to pin that values are namespaced per feature.
        registry.feature(OTHER_FEATURE).option(ALPHA).register();
        return registry.features();
    }

    private CausticaOptions load() {
        return CausticaOptions.load(configDir.resolve("caustica-options.toml"), features());
    }

    private CausticaOptions loadWithFile(String toml) throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        Files.writeString(path, toml);
        return CausticaOptions.load(path, features());
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
        CausticaOptions options = CausticaOptions.load(path, features());

        options.set(FEATURE, ALPHA, 0.75);

        assertEquals(0.75f, options.options(FEATURE).get(ALPHA));
        assertEquals(0.75f, CausticaOptions.load(path, features()).options(FEATURE).get(ALPHA),
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
        CausticaOptions options = CausticaOptions.load(path, features());

        options.apply(FEATURE, ALPHA, 0.75);

        assertEquals(0.75f, options.options(FEATURE).get(ALPHA), "the live view sees it at once");
        assertEquals(0.25f, CausticaOptions.load(path, features()).options(FEATURE).get(ALPHA),
                "a dragged slider must not reach disk on every tick");

        options.save();

        assertEquals(0.75f, CausticaOptions.load(path, features()).options(FEATURE).get(ALPHA));
        assertTrue(Files.readString(path).contains("0.75"));
    }

    @Test
    void saveWithNothingAppliedLeavesTheFileAlone() throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        Files.writeString(path, """
                ["test:options"]
                alpha = 0.5
                """);
        CausticaOptions options = CausticaOptions.load(path, features());
        String before = Files.readString(path);

        options.save();

        assertEquals(before, Files.readString(path));
    }

    @Test
    void aSecondApplyToTheSameOptionPersistsOnlyTheLastValue() throws IOException {
        Path path = configDir.resolve("caustica-options.toml");
        CausticaOptions options = CausticaOptions.load(path, features());

        options.apply(FEATURE, ALPHA, 0.4);
        options.apply(FEATURE, ALPHA, 0.6);
        options.save();

        assertEquals(0.6f, CausticaOptions.load(path, features()).options(FEATURE).get(ALPHA));
    }

    @Test
    void anUnknownFeatureThrows() {
        CausticaOptions options = load();
        ResourceId unknown = ResourceId.of("test", "missing");

        assertThrows(NullPointerException.class, () -> options.options(unknown));
        assertThrows(NullPointerException.class, () -> options.set(unknown, ALPHA, 0.5));
    }
}
