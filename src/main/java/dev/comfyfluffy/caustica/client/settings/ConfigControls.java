package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Adapts {@code CausticaConfig}'s settings to {@link SettingControl}. The declaration is the schema: bounds,
 * choices and defaults are read back off the setting, so a row can never disagree with what a write clamps.
 */
public final class ConfigControls {
    private ConfigControls() {
    }

    /** Null for a setting with no row shape a screen can render — currently only the optional strings. */
    public static SettingControl of(CausticaConfig.RuntimeSetting<?> setting) {
        return switch (setting) {
            case CausticaConfig.BooleanSetting bool -> new BoolRow(bool);
            case CausticaConfig.IntSetting integer ->
                    integer.choices().isEmpty() ? new IntRow(integer) : new IntChoiceRow(integer);
            case CausticaConfig.FloatSetting decimal -> new FloatRow(decimal);
            case CausticaConfig.StringSetting text when !text.choices().isEmpty() -> new StringChoiceRow(text);
            default -> null;
        };
    }

    /**
     * Whether this setting can be edited right now. HDR is the only case: its rows stay visible while the
     * surface offers no PQ swapchain, because a layout that changes shape between sessions is worse than a
     * greyed row that explains itself.
     */
    private static boolean editable(CausticaConfig.RuntimeSetting<?> setting) {
        if (setting == CausticaConfig.Rt.Hdr.ENABLED
                || setting == CausticaConfig.Rt.Hdr.UI_NITS
                || setting == CausticaConfig.Rt.Hdr.PEAK_NITS) {
            return CausticaConfig.Rt.Hdr.swapchainPqAvailable();
        }
        return true;
    }

    /**
     * Applied after a write. Toggling HDR has to invalidate the surface configuration or the swapchain is
     * never recreated in the other encoding; the ordinary resize path then brings it up in SDR or PQ.
     */
    private static void afterSet(CausticaConfig.RuntimeSetting<?> setting) {
        if (setting == CausticaConfig.Rt.Hdr.ENABLED) {
            net.minecraft.client.Minecraft.getInstance().invalidateSurfaceConfiguration();
        }
    }

    private record BoolRow(CausticaConfig.BooleanSetting setting) implements SettingControl.BoolControl {
        @Override
        public String id() {
            return setting.tomlPath();
        }

        @Override
        public Component label() {
            return LangKeys.settingLabel(setting);
        }

        @Override
        public Component tooltip() {
            return LangKeys.settingTooltip(setting);
        }

        @Override
        public boolean enabled() {
            return editable(setting);
        }

        @Override
        public boolean get() {
            return setting.value();
        }

        @Override
        public void set(boolean value) {
            if (setting.value() != value) {
                setting.set(value);
                afterSet(setting);
            }
        }

        @Override
        public boolean defaultValue() {
            return setting.defaultValue();
        }
    }

    private record IntRow(CausticaConfig.IntSetting setting) implements SettingControl.RangeControl {
        @Override
        public String id() {
            return setting.tomlPath();
        }

        @Override
        public Component label() {
            return LangKeys.settingLabel(setting);
        }

        @Override
        public Component tooltip() {
            return LangKeys.settingTooltip(setting);
        }

        @Override
        public boolean enabled() {
            return editable(setting);
        }

        @Override
        public double get() {
            return setting.value();
        }

        @Override
        public void set(double value) {
            setting.set((int) Math.round(value));
            afterSet(setting);
        }

        @Override
        public double defaultValue() {
            return setting.defaultValue();
        }

        @Override
        public double sliderMinimum() {
            return setting.sliderMinimum();
        }

        @Override
        public double sliderMaximum() {
            return setting.sliderMaximum();
        }

        @Override
        public double step() {
            return 1.0;
        }

        @Override
        public Component format(double value) {
            return Component.literal(Integer.toString((int) Math.round(value)));
        }
    }

    private record FloatRow(CausticaConfig.FloatSetting setting) implements SettingControl.RangeControl {
        @Override
        public String id() {
            return setting.tomlPath();
        }

        @Override
        public Component label() {
            return LangKeys.settingLabel(setting);
        }

        @Override
        public Component tooltip() {
            return LangKeys.settingTooltip(setting);
        }

        @Override
        public boolean enabled() {
            return editable(setting);
        }

        @Override
        public double get() {
            return setting.value();
        }

        @Override
        public void set(double value) {
            setting.set((float) value);
            afterSet(setting);
        }

        @Override
        public double defaultValue() {
            return setting.defaultValue();
        }

        @Override
        public double sliderMinimum() {
            return setting.sliderMinimum();
        }

        @Override
        public double sliderMaximum() {
            return setting.sliderMaximum();
        }

        @Override
        public double step() {
            return 0.0;
        }

        @Override
        public Component format(double value) {
            return Component.literal(SettingsFormat.decimal(value));
        }
    }

    private record IntChoiceRow(CausticaConfig.IntSetting setting)
            implements SettingControl.ChoiceControl<Integer> {
        @Override
        public String id() {
            return setting.tomlPath();
        }

        @Override
        public Component label() {
            return LangKeys.settingLabel(setting);
        }

        @Override
        public Component tooltip() {
            return LangKeys.settingTooltip(setting);
        }

        @Override
        public boolean enabled() {
            return editable(setting);
        }

        @Override
        public List<Integer> choices() {
            return setting.choices();
        }

        @Override
        public Integer get() {
            return setting.value();
        }

        @Override
        public void set(Integer value) {
            setting.set(value);
            afterSet(setting);
        }

        @Override
        public Integer defaultValue() {
            return setting.defaultValue();
        }

        @Override
        public Component labelOf(Integer value) {
            return LangKeys.settingChoice(setting, value);
        }
    }

    private record StringChoiceRow(CausticaConfig.StringSetting setting)
            implements SettingControl.ChoiceControl<String> {
        @Override
        public String id() {
            return setting.tomlPath();
        }

        @Override
        public Component label() {
            return LangKeys.settingLabel(setting);
        }

        @Override
        public Component tooltip() {
            return LangKeys.settingTooltip(setting);
        }

        @Override
        public boolean enabled() {
            return editable(setting);
        }

        @Override
        public List<String> choices() {
            return setting.choices();
        }

        @Override
        public String get() {
            return setting.get();
        }

        @Override
        public void set(String value) {
            setting.set(value);
            afterSet(setting);
        }

        @Override
        public String defaultValue() {
            return setting.defaultValue();
        }

        @Override
        public Component labelOf(String value) {
            return LangKeys.settingChoice(setting, value);
        }
    }
}
