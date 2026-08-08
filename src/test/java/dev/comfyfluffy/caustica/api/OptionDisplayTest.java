package dev.comfyfluffy.caustica.api;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OptionDisplayTest {
    private static final Identifier FEATURE = Identifier.fromNamespaceAndPath("test", "display");

    @Test
    void anOptionCarriesNoDisplayMetadataUntilItIsAskedFor() {
        Option<Float> option = Option.range("plain", 0.0f, 1.0f, 0.5f);

        assertSame(Option.Display.NONE, option.display());
        assertNull(option.group());
        assertFalse(option.isGroupHeader());
        assertEquals(0.0, option.step());
    }

    /** An un-narrowed slider spans the declared range, so a reader never has to handle the null case. */
    @Test
    void sliderBoundsFallBackToTheDeclaredRange() {
        Option<Float> option = Option.range("plain", 0.25f, 4.0f, 1.0f);

        assertEquals(0.25, option.sliderMinimum());
        assertEquals(4.0, option.sliderMaximum());
    }

    @Test
    void theWithersComposeWithoutDroppingEachOther() {
        Option<Float> option =
                Option.range("levels", 1.0f, 8.0f, 6.0f).inGroup("bloom").step(1.0).sliderRange(1.0, 4.0);

        assertEquals("bloom", option.group());
        assertEquals(1.0, option.step());
        assertEquals(1.0, option.sliderMinimum());
        assertEquals(4.0, option.sliderMaximum());
        assertFalse(option.isGroupHeader());
        assertEquals(8.0, option.maximum(), "a narrowed slider span must not narrow the storage clamp");
    }

    @Test
    void aGroupHeaderMustBeABool() {
        assertThrows(IllegalArgumentException.class,
                () -> Option.range("nope", 0.0f, 1.0f, 0.5f).inGroupAsHeader("bloom"));
    }

    @Test
    void onlyARangeCanDeclareAStepOrSliderRange() {
        assertThrows(IllegalArgumentException.class, () -> Option.bool("nope", true).step(1.0));
        assertThrows(IllegalArgumentException.class, () -> Option.bool("nope", true).sliderRange(0.0, 1.0));
    }

    @Test
    void aSliderRangeOutsideTheDeclaredRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Option.range("span", 0.0f, 4.0f, 1.0f).sliderRange(0.0, 8.0));
        assertThrows(IllegalArgumentException.class,
                () -> Option.range("span", 2.0f, 4.0f, 3.0f).sliderRange(1.0, 3.0));
        assertThrows(IllegalArgumentException.class,
                () -> Option.range("span", 0.0f, 4.0f, 1.0f).sliderRange(3.0, 3.0));
    }

    @Test
    void aGroupIdIsRestrictedBecauseItBecomesATranslationKeySegment() {
        assertThrows(IllegalArgumentException.class, () -> Option.bool("flag", true).inGroup("Not Valid"));
        assertThrows(IllegalArgumentException.class, () -> Option.bool("flag", true).inGroup("dotted.group"));
    }

    @Test
    void anOptionInAnUndeclaredGroupIsRejectedAtRegistration() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.feature(FEATURE).option(Option.bool("flag", true).inGroup("ghost")).register());
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void twoHeadersInOneGroupAreRejectedAtRegistration() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        assertThrows(IllegalArgumentException.class, () -> registry.feature(FEATURE)
                .group("bloom")
                .option(Option.bool("first", true).inGroupAsHeader("bloom"))
                .option(Option.bool("second", true).inGroupAsHeader("bloom"))
                .register());
    }

    /**
     * The options store resolves every declared option while loading, so without this an unsupported kind
     * is a crash during mod init rather than a message naming the limitation.
     */
    @Test
    void anOptionKindTheStoreCannotBackIsRejectedAtRegistration() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> registry.feature(FEATURE)
                .option(Option.color("tint", 0xFF8800))
                .register());
        assertTrue(e.getMessage().contains("COLOR"), e.getMessage());
        assertTrue(e.getMessage().contains("BOOL and RANGE"), e.getMessage());
    }

    @Test
    void aDeclaredGroupSurvivesOntoTheFeatureInDeclarationOrder() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        Feature feature = registry.feature(FEATURE)
                .group("bloom")
                .group("sky")
                .option(Option.bool("bloom.enabled", true).inGroupAsHeader("bloom"))
                .register();

        assertEquals(List.of("bloom", "sky"), feature.optionGroups());
    }

    @Test
    void theBuiltinFeatureDeclaresEveryGroupItsPassesUse() {
        Feature builtin = CausticaRegistry.withBuiltins().features()
                .get(Identifier.fromNamespaceAndPath("caustica", "builtin"));

        for (Option<?> option : builtin.options()) {
            assertTrue(option.group() != null && builtin.optionGroups().contains(option.group()),
                    option.id() + " is not in a declared group");
        }
    }
}
