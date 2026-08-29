package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.config.CausticaOptions;
import dev.comfyfluffy.caustica.settings.Option;
import net.minecraft.network.chat.Component;
import dev.comfyfluffy.caustica.settings.ResourceId;

/**
 * Adapts an extension's declared {@link Option}s to {@link SettingControl}. Reads go through the live view
 * so an edit shows up in the row it came from; writes go through {@link CausticaOptions#apply}, which is
 * in-memory, leaving {@link SettingsCommit} to decide when anything reaches disk.
 *
 * <p>Only {@code BOOL} and {@code RANGE} appear here because those are the only kinds the store backs, and
 * {@code SettingsBuilder} rejects the others at registration.
 */
public final class OptionControls {
    private OptionControls() {
    }

    public static SettingControl of(CausticaOptions store, ResourceId featureId, Option<?> option) {
        return switch (option.kind()) {
            case BOOL -> new BoolRow(store, featureId, cast(option));
            case RANGE -> new RangeRow(store, featureId, cast(option));
            case ENUM, COLOR -> throw new IllegalStateException(
                    "unreachable: SettingsBuilder rejects " + option.kind() + " at registration");
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Option<T> cast(Option<?> option) {
        return (Option<T>) option;
    }

    private record BoolRow(CausticaOptions store, ResourceId featureId, Option<Boolean> option)
            implements SettingControl.BoolControl {
        @Override
        public String id() {
            return option.id();
        }

        @Override
        public Component label() {
            return LangKeys.optionLabel(featureId, option);
        }

        @Override
        public Component tooltip() {
            return LangKeys.optionTooltip(featureId, option);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean get() {
            return store.options(featureId).get(option);
        }

        @Override
        public void set(boolean value) {
            store.apply(featureId, option, value);
        }

        @Override
        public boolean defaultValue() {
            return option.defaultValue();
        }
    }

    private record RangeRow(CausticaOptions store, ResourceId featureId, Option<Float> option)
            implements SettingControl.RangeControl {
        @Override
        public String id() {
            return option.id();
        }

        @Override
        public Component label() {
            return LangKeys.optionLabel(featureId, option);
        }

        @Override
        public Component tooltip() {
            return LangKeys.optionTooltip(featureId, option);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public double get() {
            return store.options(featureId).get(option);
        }

        @Override
        public void set(double value) {
            store.apply(featureId, option, value);
        }

        @Override
        public double defaultValue() {
            return option.defaultValue();
        }

        @Override
        public double sliderMinimum() {
            return option.sliderMinimum();
        }

        @Override
        public double sliderMaximum() {
            return option.sliderMaximum();
        }

        @Override
        public double step() {
            return option.step();
        }

        @Override
        public Component format(double value) {
            return Component.literal(step() == 1.0
                    ? Integer.toString((int) Math.round(value))
                    : SettingsFormat.decimal(value));
        }
    }
}
