package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaOptions;
import dev.comfyfluffy.caustica.settings.FeatureSettings;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
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
    private static final ResourceId BUILTIN = ResourceId.of("caustica", "builtin");
    private static final ResourceId PROVIDER_ONLY = ResourceId.of("test", "provider_only");
    private static final Option<Boolean> ENABLED = Option.bool("effect.enabled", true).asGroupHeader("effect");
    private static final Option<Float> STRENGTH = Option.range("effect.strength", 0.5f, 0.0f, 2.0f)
            .inGroup("effect");

    @TempDir
    Path configDir;

    private CausticaOptions options(SettingsRegistry registry) {
        return CausticaOptions.load(configDir.resolve("caustica-options.toml"), registry);
    }

    private static SettingsRegistry registry() {
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(BUILTIN).group("effect").options(List.of(ENABLED, STRENGTH)).register();
        registry.feature(PROVIDER_ONLY).register();
        return registry;
    }

    private static SettingsSection sectionOf(List<SettingsSection> sections, String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst().orElseThrow();
    }

    private static SettingGroup groupOf(SettingsSection section, String id) {
        return section.groups().stream().filter(group -> group.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everySettingsFeatureWithOptionsBecomesItsOwnSection() {
        SettingsRegistry registry = registry();
        List<SettingsSection> sections = CausticaSections.build(registry, options(registry));

        assertEquals("engine", sections.getFirst().id());
        assertNotNull(sectionOf(sections, BUILTIN.toString()));
    }

    @Test
    void aFeatureWithNoOptionsGetsNoSection() {
        SettingsRegistry registry = registry();
        List<SettingsSection> sections = CausticaSections.build(registry, options(registry));

        assertTrue(sections.stream().noneMatch(section -> section.id().equals(PROVIDER_ONLY.toString())));
    }

    @Test
    void theHeaderBoolIsSeparatedFromTheRowsItCollapses() {
        SettingsRegistry registry = registry();
        FeatureSettings builtin = registry.settings(BUILTIN);
        SettingGroup effect = groupOf(CausticaSections.feature(builtin, options(registry)), "effect");

        assertNotNull(effect.header());
        assertEquals("effect.enabled", effect.header().id());
        assertEquals(List.of("effect.strength"), effect.rows().stream().map(SettingControl::id).toList());
    }

    @Test
    void aGroupWithNoHeaderCollapsesOnlyByCaret() {
        SettingsRegistry registry = new SettingsRegistry();
        FeatureSettings feature = registry.feature(BUILTIN).group("effect").option(STRENGTH).register();
        SettingGroup effect = groupOf(CausticaSections.feature(feature, options(registry)), "effect");

        assertNull(effect.header());
        assertTrue(effect.rowsVisible(true));
        assertFalse(effect.rowsVisible(false));
    }

    @Test
    void aHeaderBoolDecidesVisibilityRegardlessOfTheCaret() {
        SettingsRegistry registry = registry();
        SettingGroup effect = groupOf(
                CausticaSections.feature(registry.settings(BUILTIN), options(registry)), "effect");

        effect.header().set(false);
        assertFalse(effect.rowsVisible(true));
        effect.header().set(true);
        assertTrue(effect.rowsVisible(false));
    }

    @Test
    void theEngineSectionShowsOnlySettingsThatOptedIntoAGroup() {
        SettingsSection engine = CausticaSections.engine();

        assertFalse(engine.groups().isEmpty());
        List<String> ids = engine.allControls().stream().map(SettingControl::id).toList();
        assertTrue(ids.contains("composite.max-bounces"));
        assertTrue(ids.contains("hdr.enabled"));
        assertFalse(ids.contains("worker-threads"));
        assertFalse(ids.contains("ngx.path"));
    }

    @Test
    void resettingASectionRestoresEveryDeclaredDefault() {
        SettingsRegistry registry = registry();
        SettingsSection section = CausticaSections.feature(registry.settings(BUILTIN), options(registry));
        SettingControl.RangeControl strength = (SettingControl.RangeControl) section.allControls().stream()
                .filter(row -> row.id().equals(STRENGTH.id())).findFirst().orElseThrow();

        strength.set(1.5);
        assertTrue(section.isModified());
        section.reset();

        assertFalse(section.isModified());
        assertEquals(STRENGTH.defaultValue(), (float) strength.get());
    }

    @Test
    void featureAccentsAreStable() {
        ResourceId thirdParty = ResourceId.of("someone", "else");

        assertEquals(CausticaSections.accentFor(thirdParty), CausticaSections.accentFor(thirdParty));
        assertEquals(0xFFFFB74D, CausticaSections.accentFor(BUILTIN));
    }

    @Test
    void anUngroupedOptionStillGetsARow() {
        SettingsRegistry registry = new SettingsRegistry();
        Option<Boolean> loose = Option.bool("loose", true);
        FeatureSettings feature = registry.feature(ResourceId.of("test", "loose")).option(loose).register();
        SettingGroup other = groupOf(CausticaSections.feature(feature, options(registry)), "other");

        assertEquals(List.of("loose"), other.rows().stream().map(SettingControl::id).toList());
    }
}
