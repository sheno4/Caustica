package dev.comfyfluffy.caustica.client.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.Slot;
import dev.comfyfluffy.caustica.api.Slots;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every key the screen derives has to exist in {@code en_us.json}, because a missing one renders as the raw
 * key in-game rather than failing anywhere. The keys are derived and the entries are hand-written, so this
 * is the seam where the two drift apart.
 */
final class LangKeysTest {
    private static JsonObject lang() throws IOException {
        try (InputStream stream = LangKeysTest.class.getResourceAsStream("/assets/caustica/lang/en_us.json")) {
            assertNotNull(stream, "en_us.json is not on the test classpath");
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The key a translatable component would look up, so the test checks what the screen actually renders. */
    private static String keyOf(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    private final List<String> checked = new ArrayList<>();

    private void require(JsonObject lang, List<String> missing, Component component) {
        String key = keyOf(component);
        checked.add(key);
        if (!lang.has(key)) {
            missing.add(key);
        }
    }

    @Test
    void everyDerivedKeyHasAnEnglishEntry() throws IOException {
        JsonObject lang = lang();
        CausticaRegistry registry = CausticaRegistry.withBuiltins();
        CausticaConfig.ensureRegistered();
        List<String> missing = new ArrayList<>();

        for (CausticaConfig.RuntimeSetting<?> setting : CausticaConfig.settings()) {
            if (setting.group() == null) {
                continue;
            }
            require(lang, missing, LangKeys.settingLabel(setting));
            require(lang, missing, LangKeys.settingTooltip(setting));
            if (setting instanceof CausticaConfig.IntSetting integer) {
                integer.choices().forEach(
                        choice -> require(lang, missing, LangKeys.settingChoice(setting, choice)));
            }
            if (setting instanceof CausticaConfig.StringSetting text) {
                text.choices().forEach(choice -> require(lang, missing, LangKeys.settingChoice(setting, choice)));
            }
        }

        for (Feature feature : registry.features().values()) {
            for (Option<?> option : feature.options()) {
                require(lang, missing, LangKeys.optionLabel(feature.id(), option));
                require(lang, missing, LangKeys.optionTooltip(feature.id(), option));
            }
            for (String group : feature.optionGroups()) {
                require(lang, missing, LangKeys.optionGroup(feature.id(), group));
            }
        }

        for (Slot slot : Slots.ALL) {
            require(lang, missing, LangKeys.slotLabel(slot));
            require(lang, missing, LangKeys.slotTooltip(slot));
        }

        assertTrue(missing.isEmpty(), "missing en_us.json entries: " + missing);
        // Guards against the loops above silently iterating nothing and passing vacuously.
        assertTrue(checked.size() > 50, "only checked " + checked.size() + " keys");
        assertTrue(checked.contains("caustica.setting.composite.max-bounces"), checked.toString());
        assertTrue(checked.contains("caustica.option.caustica.builtin.bloom.strength"), checked.toString());
        assertTrue(checked.contains("caustica.slot.caustica.sky"), checked.toString());
    }

    /** The shell's own strings, which no derivation reaches. */
    @Test
    void theScreenChromeHasEntries() throws IOException {
        JsonObject lang = lang();
        List<String> missing = new ArrayList<>();
        for (String key : List.of(
                "caustica.options.open", "caustica.screen.title", "caustica.screen.done",
                "caustica.screen.reset_section", "caustica.screen.close",
                "caustica.screen.group.expanded", "caustica.screen.group.collapsed",
                "caustica.nav.engine", "caustica.nav.extensions",
                "caustica.section.engine", "caustica.section.composition",
                "caustica.group.other", "caustica.slot.default_suffix")) {
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "missing en_us.json entries: " + missing);
    }

    /** Engine group titles come from a list in CausticaSections, not from any setting's own declaration. */
    @Test
    void everyEngineGroupThatHasRowsHasATitle() throws IOException {
        JsonObject lang = lang();
        List<String> missing = new ArrayList<>();
        SettingsSection engine = CausticaSections.engine();

        assertFalse(engine.groups().isEmpty());
        engine.groups().forEach(group -> require(lang, missing, group.title()));
        assertTrue(missing.isEmpty(), "missing en_us.json entries: " + missing);
    }
}
