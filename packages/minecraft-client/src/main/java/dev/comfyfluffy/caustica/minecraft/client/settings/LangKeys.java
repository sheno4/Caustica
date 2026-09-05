package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.settings.Option;
import net.minecraft.network.chat.Component;
import dev.comfyfluffy.caustica.settings.ResourceId;

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

    /** {@code caustica.group.engine.<groupId>}. */
    public static Component engineGroup(String groupId) {
        return Component.translatable("caustica.group.engine." + groupId);
    }

    /**
     * Renderer options use {@code caustica.setting.<optionId>}; extensions use
     * {@code caustica.option.<namespace>.<path>.<optionId>}.
     */
    public static Component optionLabel(ResourceId featureId, Option<?> option) {
        return Component.translatable(optionKey(featureId, option));
    }

    public static Component optionTooltip(ResourceId featureId, Option<?> option) {
        return Component.translatable(optionKey(featureId, option) + ".tooltip");
    }

    public static Component optionChoice(ResourceId featureId, Option<?> option, Object value) {
        return Component.translatable(optionKey(featureId, option) + "." + value);
    }

    private static String optionKey(ResourceId featureId, Option<?> option) {
        if (featureId.equals(CausticaConfig.FEATURE)) return "caustica.setting." + option.id();
        return "caustica.option." + featureId.namespace() + "." + featureId.path() + "." + option.id();
    }

    /** {@code caustica.group.<namespace>.<path>.<groupId>}. */
    public static Component optionGroup(ResourceId featureId, String groupId) {
        return Component.translatable(
                "caustica.group." + featureId.namespace() + "." + featureId.path() + "." + groupId);
    }

    /** A feature's own name, reusing the convention {@code BuiltinExtension} already declares. */
    public static Component featureDescription(ResourceId featureId) {
        return Component.translatable(
                "feature." + featureId.namespace() + "." + featureId.path() + ".description");
    }
}
