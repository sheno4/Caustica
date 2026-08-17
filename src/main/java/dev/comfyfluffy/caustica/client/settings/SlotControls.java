package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Slot;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.minecraft.MinecraftDisplayText;
import net.minecraft.network.chat.Component;
import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.List;

/**
 * Adapts a slot's candidate bindings to a {@link SettingControl.ChoiceControl}. Two candidates for one slot
 * are not a conflict to resolve, they are a choice to offer — which is why the engine never picks between
 * them and the default is always the built-in, so installing an extension changes nothing until asked.
 *
 * <p>Selecting writes straight to the registry, where the RT runtime notices the composition
 * changed and rebuilds the world pipeline, and mirrors the id into {@code caustica.toml} so it survives a
 * restart. Reverting to the default clears the saved id rather than writing the default's own id, so a slot
 * left alone keeps tracking whatever the built-in default becomes.
 */
public final class SlotControls {
    private SlotControls() {
    }

    public static SettingControl.ChoiceControl<ResourceId> of(CausticaRegistry registry, Slot slot) {
        return new SlotRow(registry, slot, persistedSetting(slot));
    }

    /** Null for a slot with no config key, which then switches for the session but does not persist. */
    private static CausticaConfig.OptionalStringSetting persistedSetting(Slot slot) {
        if (slot.equals(Slots.SKY)) {
            return CausticaConfig.Rt.Composition.SKY;
        }
        return null;
    }

    private record SlotRow(CausticaRegistry registry, Slot slot, CausticaConfig.OptionalStringSetting persisted)
            implements SettingControl.ChoiceControl<ResourceId> {
        @Override
        public String id() {
            return slot.id().toString();
        }

        @Override
        public Component label() {
            return LangKeys.slotLabel(slot);
        }

        @Override
        public Component tooltip() {
            return LangKeys.slotTooltip(slot);
        }

        @Override
        public boolean enabled() {
            return choices().size() > 1;
        }

        @Override
        public List<ResourceId> choices() {
            return registry.candidates(slot);
        }

        @Override
        public ResourceId get() {
            return registry.selectedFeature(slot);
        }

        @Override
        public void set(ResourceId value) {
            if (value.equals(registry.defaultFeature(slot))) {
                registry.selectDefault(slot);
                if (persisted != null) {
                    persisted.set(null);
                }
                return;
            }
            registry.select(slot, value);
            if (persisted != null) {
                persisted.set(value.toString());
            }
        }

        @Override
        public ResourceId defaultValue() {
            return registry.defaultFeature(slot);
        }

        /** The default is marked in the label, since a dropdown shows one entry at a time. */
        @Override
        public Component labelOf(ResourceId value) {
            Component title = MinecraftDisplayText.component(registry.features().get(value).title());
            if (!value.equals(registry.defaultFeature(slot))) {
                return title;
            }
            return Component.empty().append(title).append(" (")
                    .append(Component.translatable("caustica.slot.default_suffix")).append(")");
        }

        @Override
        public boolean isModified() {
            return !registry.isDefaultSelected(slot);
        }
    }
}
