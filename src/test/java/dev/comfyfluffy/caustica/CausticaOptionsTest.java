package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.pass.PassOptions;
import net.minecraft.resources.Identifier;
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
    private static final Identifier FEATURE = Identifier.fromNamespaceAndPath("test", "options");
    private static final Identifier OTHER_FEATURE = Identifier.fromNamespaceAndPath("test", "other");
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

    private Map<Identifier, Feature> features() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
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
        PassOptions options = load().options(FEATURE);

        assertEquals(0.25f, options.get(ALPHA));
        assertEquals(true, options.get(FLAG));
        assertEquals(4.0f, options.get(NESTED));
    }

    @Test
    void aFileValueOverridesTheDefaultAndIsClampedToTheOptionsRange() throws IOException {
        PassOptions options = loadWithFile("""
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
        Map<String, Object> frame = options.snapshot();

        options.set(FEATURE, ALPHA, 0.75);

        assertEquals(0.25f, options.view(FEATURE, frame).get(ALPHA),
                "a frame holding a snapshot must not see a mid-frame write");
        assertEquals(0.75f, options.options(FEATURE).get(ALPHA),
                "the live view must see it immediately");
    }

    @Test
    void readingAnOptionTheFeatureNeverDeclaredThrows() {
        PassOptions options = load().options(FEATURE);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> options.get(UNDECLARED));
        assertTrue(e.getMessage().contains("undeclared"), e.getMessage());
    }

    @Test
    void readingALookalikeOptionWithADifferentDeclarationThrows() {
        PassOptions options = load().options(FEATURE);

        assertThrows(IllegalArgumentException.class, () -> options.get(ALPHA_LOOKALIKE),
                "a token sharing an id but not the declaration must not silently resolve");
    }

    @Test
    void anUnknownFeatureThrows() {
        CausticaOptions options = load();
        Identifier unknown = Identifier.fromNamespaceAndPath("test", "missing");

        assertThrows(NullPointerException.class, () -> options.options(unknown));
        assertThrows(NullPointerException.class, () -> options.set(unknown, ALPHA, 0.5));
    }
}
