package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.builtin.BloomPass;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaSectionsTest {
    // BuiltinExtension is package-private, and widening it for a test would be the wrong trade.
    private static final Identifier BUILTIN = Identifier.fromNamespaceAndPath("caustica", "builtin");
    private static final Identifier PROVIDER_ONLY = Identifier.fromNamespaceAndPath("test", "provider_only");

    @TempDir
    Path configDir;

    private CausticaOptions options(CausticaRegistry registry) {
        return CausticaOptions.load(configDir.resolve("caustica-options.toml"), registry.features());
    }

    private static SettingsSection sectionOf(List<SettingsSection> sections, String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst().orElseThrow();
    }

    private static SettingGroup groupOf(SettingsSection section, String id) {
        return section.groups().stream().filter(group -> group.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyRegisteredFeatureWithOptionsBecomesItsOwnSection() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        List<SettingsSection> sections = CausticaSections.build(registry, options(registry));

        assertEquals("engine", sections.get(0).id());
        assertEquals("composition", sections.get(1).id());
        assertNotNull(sectionOf(sections, BUILTIN.toString()));
    }

    /** A scene-provider-only extension has nothing to render, so it must not leave an empty page behind. */
    @Test
    void aFeatureWithNoOptionsGetsNoSection() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        registry.feature(PROVIDER_ONLY).register();

        List<SettingsSection> sections = CausticaSections.build(registry, options(registry));

        assertTrue(sections.stream().noneMatch(section -> section.id().equals(PROVIDER_ONLY.toString())));
    }

    @Test
    void theHeaderBoolIsSeparatedFromTheRowsItCollapses() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Feature builtin = registry.features().get(BUILTIN);

        SettingsSection section = CausticaSections.feature(builtin, options(registry));
        SettingGroup bloom = groupOf(section, "bloom");

        assertNotNull(bloom.header(), "bloom.enabled is the group's header");
        assertEquals("bloom.enabled", bloom.header().id());
        assertTrue(bloom.rows().stream().noneMatch(row -> row.id().equals("bloom.enabled")),
                "the header must not also appear as an ordinary row");
        assertEquals(BloomPass.OPTIONS.size(), bloom.allControls().size());
    }

    @Test
    void aGroupWithNoHeaderCollapsesOnlyByCaret() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Feature builtin = registry.features().get(BUILTIN);

        SettingGroup sky = groupOf(CausticaSections.feature(builtin, options(registry)), "sky");

        assertNull(sky.header());
        assertTrue(sky.rowsVisible(true));
        assertFalse(sky.rowsVisible(false));
    }

    @Test
    void aHeaderBoolDecidesVisibilityRegardlessOfTheCaret() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Feature builtin = registry.features().get(BUILTIN);
        SettingGroup bloom = groupOf(CausticaSections.feature(builtin, options(registry)), "bloom");

        bloom.header().set(false);
        assertFalse(bloom.rowsVisible(true));

        bloom.header().set(true);
        assertTrue(bloom.rowsVisible(false));
    }

    @Test
    void groupsFollowTheFeaturesDeclaredOrder() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Feature builtin = registry.features().get(BUILTIN);

        SettingsSection section = CausticaSections.feature(builtin, options(registry));

        assertEquals(builtin.optionGroups(), section.groups().stream().map(SettingGroup::id).toList());
    }

    @Test
    void theEngineSectionShowsOnlySettingsThatOptedIntoAGroup() {
        SettingsSection engine = CausticaSections.engine();

        assertFalse(engine.groups().isEmpty());
        List<String> ids = engine.allControls().stream().map(SettingControl::id).toList();
        assertTrue(ids.contains("composite.max-bounces"));
        assertTrue(ids.contains("hdr.enabled"));
        assertFalse(ids.contains("worker-threads"), "a startup-only knob has no row");
        assertFalse(ids.contains("ngx.path"), "an optional string has no row shape");
    }

    @Test
    void everySlotBecomesItsOwnGroupOnTheCompositionSection() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();

        SettingsSection composition = CausticaSections.composition(registry);

        assertEquals(List.of("sky", "surface"),
                composition.groups().stream().map(SettingGroup::id).toList());
        SettingGroup sky = groupOf(composition, "sky");
        assertEquals(List.of("caustica:sky"), sky.rows().stream().map(SettingControl::id).toList());

        SettingControl.ChoiceControl<?> control = (SettingControl.ChoiceControl<?>) sky.rows().get(0);
        assertEquals(List.of(BUILTIN), control.choices());
        assertFalse(control.enabled(), "a slot with one candidate offers no choice to make");
    }

    @Test
    void resettingASectionRestoresEveryDeclaredDefault() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Feature builtin = registry.features().get(BUILTIN);
        SettingsSection section = CausticaSections.feature(builtin, options(registry));
        SettingControl.RangeControl strength = (SettingControl.RangeControl) section.allControls().stream()
                .filter(row -> row.id().equals("bloom.strength")).findFirst().orElseThrow();

        assertFalse(section.isModified());
        strength.set(1.5);
        assertTrue(section.isModified());

        section.reset();

        assertFalse(section.isModified());
        assertEquals(BloomPass.STRENGTH.defaultValue(), (float) strength.get());
    }

    @Test
    void aThirdPartyFeatureGetsAStableAccentAcrossLaunches() {
        Identifier featureId = Identifier.fromNamespaceAndPath("someone", "else");

        assertEquals(CausticaSections.accentFor(featureId), CausticaSections.accentFor(featureId));
        assertEquals(0xFFFFB74D, CausticaSections.accentFor(BUILTIN));
    }

    @Test
    void anUngroupedOptionStillGetsARow() {
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        Identifier featureId = Identifier.fromNamespaceAndPath("test", "loose");
        Feature feature = registry.feature(featureId).option(Option.bool("loose", true)).register();

        SettingGroup other = groupOf(CausticaSections.feature(feature, options(registry)), "other");

        assertEquals(List.of("loose"), other.rows().stream().map(SettingControl::id).toList());
    }
}
