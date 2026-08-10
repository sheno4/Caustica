package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.Slot;
import net.minecraft.network.chat.Component;
import dev.comfyfluffy.caustica.api.ResourceId;

/**
 * Every translation key the settings screen uses, derived rather than declared. An extension gets labelled
 * rows by registering options and adding lang entries — there is no place to hand the engine a label, which
 * is what keeps {@code Option} free of display text.
 *
 * <p>{@code Component.translatable} renders a missing key verbatim, so a gap shows up in-game as the key
 * itself rather than as blank space.
 */
public final class LangKeys {
    private LangKeys() {
    }

    /** {@code caustica.setting.<tomlPath>}, e.g. {@code caustica.setting.composite.max-bounces}. */
    public static Component settingLabel(CausticaConfig.RuntimeSetting<?> setting) {
        return Component.translatable(setting.translationKey());
    }

    public static Component settingTooltip(CausticaConfig.RuntimeSetting<?> setting) {
        return Component.translatable(setting.translationKey() + ".tooltip");
    }

    /** {@code caustica.setting.<tomlPath>.<rawValue>}, e.g. {@code caustica.setting.exposure.mode.auto}. */
    public static Component settingChoice(CausticaConfig.RuntimeSetting<?> setting, Object value) {
        return Component.translatable(setting.translationKey() + "." + value);
    }

    /** {@code caustica.group.engine.<groupId>}. */
    public static Component engineGroup(String groupId) {
        return Component.translatable("caustica.group.engine." + groupId);
    }

    /**
     * {@code caustica.option.<namespace>.<path>.<optionId>}. Option ids may contain dots
     * ({@code bloom.strength}), which nest naturally in the key rather than colliding.
     */
    public static Component optionLabel(ResourceId featureId, Option<?> option) {
        return Component.translatable(optionKey(featureId, option));
    }

    public static Component optionTooltip(ResourceId featureId, Option<?> option) {
        return Component.translatable(optionKey(featureId, option) + ".tooltip");
    }

    private static String optionKey(ResourceId featureId, Option<?> option) {
        return "caustica.option." + featureId.namespace() + "." + featureId.path() + "." + option.id();
    }

    /** {@code caustica.group.<namespace>.<path>.<groupId>}. */
    public static Component optionGroup(ResourceId featureId, String groupId) {
        return Component.translatable(
                "caustica.group." + featureId.namespace() + "." + featureId.path() + "." + groupId);
    }

    /** {@code caustica.slot.<namespace>.<path>}. */
    public static Component slotLabel(Slot slot) {
        return Component.translatable(slotKey(slot));
    }

    public static Component slotTooltip(Slot slot) {
        return Component.translatable(slotKey(slot) + ".tooltip");
    }

    private static String slotKey(Slot slot) {
        return "caustica.slot." + slot.id().namespace() + "." + slot.id().path();
    }

    /** A feature's own name, reusing the convention {@code BuiltinExtension} already declares. */
    public static Component featureDescription(ResourceId featureId) {
        return Component.translatable(
                "feature." + featureId.namespace() + "." + featureId.path() + ".description");
    }
}
