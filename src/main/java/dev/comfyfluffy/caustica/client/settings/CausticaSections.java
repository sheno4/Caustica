package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.Slot;
import dev.comfyfluffy.caustica.api.Slots;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the screen's pages from the two stores and the registry. Nothing here knows about widgets, so the
 * whole model is exercised in tests without a GUI stack.
 */
public final class CausticaSections {
    /**
     * Engine groups in the order they appear. Explicit because {@code RuntimeSetting.group()} carries the
     * membership but not the ordering, and a screen that reorders itself between launches is worse than a
     * list to maintain.
     */
    private static final List<String> ENGINE_GROUPS =
            List.of("general", "quality", "upscaling", "exposure", "look", "output", "entities", "debug");

    private static final int ACCENT_ENGINE = 0xFF4FC3F7;
    private static final int ACCENT_COMPOSITION = 0xFFB388FF;
    private static final int ACCENT_BUILTIN = 0xFFFFB74D;
    /**
     * Accents for third-party features, picked by a stable hash so a given extension keeps its colour across
     * launches. Hues from Minecraft's own material vocabulary rather than arbitrary saturated colour.
     */
    private static final int[] ACCENT_WHEEL = {
            0xFF81C784, 0xFFF06292, 0xFF4DD0E1, 0xFFFFD54F,
            0xFFA1887F, 0xFF9575CD, 0xFF4DB6AC, 0xFFFF8A65};

    private CausticaSections() {
    }

    public static List<SettingsSection> build(CausticaRegistry registry, CausticaOptions options) {
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(engine());
        sections.add(composition(registry));
        for (Feature feature : registry.features().values()) {
            SettingsSection section = feature(feature, options);
            if (section != null) {
                sections.add(section);
            }
        }
        return List.copyOf(sections);
    }

    static SettingsSection engine() {
        CausticaConfig.ensureRegistered();
        Map<String, List<SettingControl>> byGroup = new LinkedHashMap<>();
        for (String group : ENGINE_GROUPS) {
            byGroup.put(group, new ArrayList<>());
        }
        for (CausticaConfig.RuntimeSetting<?> setting : CausticaConfig.settings()) {
            List<SettingControl> rows = byGroup.get(setting.group());
            if (rows == null) {
                continue;
            }
            SettingControl control = ConfigControls.of(setting);
            if (control != null) {
                rows.add(control);
            }
        }
        List<SettingGroup> groups = new ArrayList<>();
        byGroup.forEach((id, rows) -> {
            if (!rows.isEmpty()) {
                groups.add(new SettingGroup(id, LangKeys.engineGroup(id), null, rows));
            }
        });
        return new SettingsSection("engine", Component.translatable("caustica.section.engine"),
                ACCENT_ENGINE, groups);
    }

    /**
     * One group per slot rather than one group of slots: the group title is what names the slot its
     * candidates belong to, so a page of radio rows stays readable once more than one slot has a choice.
     */
    static SettingsSection composition(CausticaRegistry registry) {
        List<SettingGroup> groups = new ArrayList<>();
        for (Slot slot : Slots.ALL) {
            groups.add(new SettingGroup(slot.id().getPath(), LangKeys.slotLabel(slot), null,
                    List.of(SlotControls.of(registry, slot))));
        }
        return new SettingsSection("composition", Component.translatable("caustica.section.composition"),
                ACCENT_COMPOSITION, groups);
    }

    /** Null for a feature that declares no options — a provider-only extension has nothing to show. */
    static SettingsSection feature(Feature feature, CausticaOptions options) {
        if (feature.options().isEmpty()) {
            return null;
        }
        List<SettingGroup> groups = new ArrayList<>();
        for (String groupId : feature.optionGroups()) {
            SettingControl.BoolControl header = null;
            List<SettingControl> rows = new ArrayList<>();
            for (Option<?> option : feature.options()) {
                if (!groupId.equals(option.group())) {
                    continue;
                }
                SettingControl control = OptionControls.of(options, feature.id(), option);
                if (option.isGroupHeader()) {
                    header = (SettingControl.BoolControl) control;
                } else {
                    rows.add(control);
                }
            }
            if (header != null || !rows.isEmpty()) {
                groups.add(new SettingGroup(groupId, LangKeys.optionGroup(feature.id(), groupId), header, rows));
            }
        }
        List<SettingControl> ungrouped = new ArrayList<>();
        for (Option<?> option : feature.options()) {
            if (option.group() == null) {
                ungrouped.add(OptionControls.of(options, feature.id(), option));
            }
        }
        if (!ungrouped.isEmpty()) {
            groups.add(new SettingGroup("other", Component.translatable("caustica.group.other"), null, ungrouped));
        }
        return new SettingsSection(feature.id().toString(), feature.title(), accentFor(feature.id()), groups);
    }

    static int accentFor(Identifier featureId) {
        if (featureId.equals(Identifier.fromNamespaceAndPath("caustica", "builtin"))) {
            return ACCENT_BUILTIN;
        }
        return ACCENT_WHEEL[Math.floorMod(featureId.hashCode(), ACCENT_WHEEL.length)];
    }
}
