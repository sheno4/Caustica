package dev.comfyfluffy.caustica.settings;

import java.util.List;
import java.util.Objects;

public record Option<T>(String id, Kind kind, T defaultValue, Double minimum, Double maximum,
                        List<T> choices, Display display) {
    public enum Kind {
        BOOL,
        RANGE,
        ENUM,
        COLOR
    }

    /**
     * Settings-screen presentation metadata. {@code group} identifies a group declared with
     * {@link SettingsBuilder#group}; {@code groupHeader} marks its controlling boolean option.
     * {@code step} is the range quantization, where zero means continuous. Null slider bounds use the
     * option's declared range.
     */
    public record Display(String group, boolean groupHeader, double step,
                          Double sliderMinimum, Double sliderMaximum) {
        public static final Display NONE = new Display(null, false, 0.0, null, null);
    }

    public Option {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(display, "display");
        choices = List.copyOf(choices);
        if (!id.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid option id: " + id);
        }
        if (kind == Kind.RANGE && (minimum == null || maximum == null || minimum > maximum)) {
            throw new IllegalArgumentException("range option requires an ordered minimum and maximum");
        }
        if (kind == Kind.ENUM && (choices.isEmpty() || !choices.contains(defaultValue))) {
            throw new IllegalArgumentException("enum option choices must contain its default");
        }
        if (display.groupHeader() && (kind != Kind.BOOL || display.group() == null)) {
            throw new IllegalArgumentException(id + ": a group header must be a bool option in a group");
        }
        if (display.step() > 0.0 && kind != Kind.RANGE) {
            throw new IllegalArgumentException(id + ": only a range option can declare a step");
        }
        if (display.sliderMinimum() != null || display.sliderMaximum() != null) {
            if (kind != Kind.RANGE) {
                throw new IllegalArgumentException(id + ": only a range option can declare a slider range");
            }
            double low = display.sliderMinimum() != null ? display.sliderMinimum() : minimum;
            double high = display.sliderMaximum() != null ? display.sliderMaximum() : maximum;
            if (low < minimum || high > maximum || low >= high) {
                throw new IllegalArgumentException(
                        id + ": a slider range must be an ordered span inside the declared range");
            }
        }
    }

    /** The collapsible group this option belongs to, or null when it is ungrouped. */
    public String group() {
        return display.group();
    }

    /** Whether this option's value decides that its group's other options are shown. */
    public boolean isGroupHeader() {
        return display.groupHeader();
    }

    /** Quantisation of a {@code RANGE}; 0 for continuous. */
    public double step() {
        return display.step();
    }

    /**
     * Returns the slider's lower bound, which may be narrower than the option's legal range.
     */
    public double sliderMinimum() {
        return display.sliderMinimum() != null ? display.sliderMinimum() : minimum;
    }

    public double sliderMaximum() {
        return display.sliderMaximum() != null ? display.sliderMaximum() : maximum;
    }

    public static Option<Boolean> bool(String id, boolean defaultValue) {
        return new Option<>(id, Kind.BOOL, defaultValue, null, null, List.of(), Display.NONE);
    }

    public static Option<Float> range(String id, float minimum, float maximum, float defaultValue) {
        if (defaultValue < minimum || defaultValue > maximum) {
            throw new IllegalArgumentException("range default is outside its bounds");
        }
        return new Option<>(id, Kind.RANGE, defaultValue, (double) minimum, (double) maximum,
                List.of(), Display.NONE);
    }

    public static <E extends Enum<E>> Option<E> enumOf(String id, E defaultValue, List<E> choices) {
        return new Option<>(id, Kind.ENUM, defaultValue, null, null, choices, Display.NONE);
    }

    public static Option<Integer> color(String id, int defaultRgb) {
        return new Option<>(id, Kind.COLOR, defaultRgb, null, null, List.of(), Display.NONE);
    }

    /** Places this option in a collapsible group the owning feature declared. */
    public Option<T> inGroup(String group) {
        return withDisplay(new Display(requireGroupId(group), display.groupHeader(), display.step(),
                display.sliderMinimum(), display.sliderMaximum()));
    }

    /** Places this option in {@code group} and makes it the bool that collapses the group's other rows. */
    public Option<T> inGroupAsHeader(String group) {
        return withDisplay(new Display(requireGroupId(group), true, display.step(),
                display.sliderMinimum(), display.sliderMaximum()));
    }

    /** Validates and returns an option-group id. */
    public static String requireGroupId(String group) {
        Objects.requireNonNull(group, "group");
        if (!group.matches("[a-z][a-z0-9_-]*")) {
            throw new IllegalArgumentException("invalid option group id: " + group);
        }
        return group;
    }

    public Option<T> step(double step) {
        return withDisplay(new Display(display.group(), display.groupHeader(), step,
                display.sliderMinimum(), display.sliderMaximum()));
    }

    /** Narrows the span a slider covers without narrowing the declared range's storage clamp. */
    public Option<T> sliderRange(double sliderMinimum, double sliderMaximum) {
        return withDisplay(new Display(display.group(), display.groupHeader(), display.step(),
                sliderMinimum, sliderMaximum));
    }

    private Option<T> withDisplay(Display updated) {
        return new Option<>(id, kind, defaultValue, minimum, maximum, choices, updated);
    }
}
