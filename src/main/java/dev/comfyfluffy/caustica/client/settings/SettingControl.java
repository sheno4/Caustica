package dev.comfyfluffy.caustica.client.settings;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * One editable row, independent of which store backs it. Caustica keeps engine settings
 * ({@code CausticaConfig}) and extension-declared options ({@code CausticaOptions}) in separate stores
 * because they differ in every way that matters to them — a fixed compile-time schema read on hot paths as
 * a volatile field, against a runtime-declared one resolved through a map — but they do not differ in any
 * way that matters to a slider. This is the seam between the two, and the only thing the widgets see.
 *
 * <p>Writes here are in-memory and take effect on the next frame; {@link SettingsCommit} decides when they
 * reach disk.
 */
public sealed interface SettingControl {
    /** Stable identity for debugging and test assertions, not shown to the player. */
    String id();

    Component label();

    /** May be {@link Component#empty()}. */
    Component tooltip();

    /** False greys the row out — a setting whose hardware or mode prerequisite is absent. */
    boolean enabled();

    /** Whether the value differs from the declaration's default, so a screen can offer to undo it. */
    boolean isModified();

    void reset();

    non-sealed interface BoolControl extends SettingControl {
        boolean get();

        void set(boolean value);

        boolean defaultValue();

        @Override
        default boolean isModified() {
            return get() != defaultValue();
        }

        @Override
        default void reset() {
            set(defaultValue());
        }
    }

    /**
     * A numeric row. {@code step} of 0 is continuous, 1 an integer count. The slider span is deliberately
     * allowed to be narrower than what a write accepts: a range whose useful values occupy a small part of
     * its legal range is unusable as a linear slider, and narrowing the stored clamp instead would truncate
     * a value someone set on purpose. A loaded value outside the span pins the knob at that end while the
     * label keeps reading the true number.
     */
    non-sealed interface RangeControl extends SettingControl {
        double get();

        void set(double value);

        double defaultValue();

        double sliderMinimum();

        double sliderMaximum();

        double step();

        /** How the value reads to a player, including any unit. */
        Component format(double value);

        /** Value to slider position, clamped into 0..1 so an out-of-span value pins rather than overflows. */
        default double toSlider(double value) {
            double span = sliderMaximum() - sliderMinimum();
            return Math.clamp((value - sliderMinimum()) / span, 0.0, 1.0);
        }

        /** Slider position to value, quantised to {@link #step()}. */
        default double fromSlider(double position) {
            double raw = sliderMinimum() + Math.clamp(position, 0.0, 1.0) * (sliderMaximum() - sliderMinimum());
            if (step() <= 0.0) {
                return raw;
            }
            return sliderMinimum() + Math.round((raw - sliderMinimum()) / step()) * step();
        }

        @Override
        default boolean isModified() {
            return get() != defaultValue();
        }

        @Override
        default void reset() {
            set(defaultValue());
        }
    }

    /** A closed set of values — an exposure mode, a DLSS quality step, or which feature owns a slot. */
    non-sealed interface ChoiceControl<T> extends SettingControl {
        List<T> choices();

        T get();

        void set(T value);

        T defaultValue();

        Component labelOf(T value);

        @Override
        default boolean isModified() {
            return !get().equals(defaultValue());
        }

        @Override
        default void reset() {
            set(defaultValue());
        }
    }
}
