package dev.comfyfluffy.caustica.settings;

/** Process-scoped settings reads and preference edits. Frame consumers retain read-only snapshots. */
public interface SettingsAccess extends OptionLookup {
    /** Changes a preference using the option's normalization rules without writing to disk. */
    void apply(ResourceId feature, Option<?> option, Object value);

    /** Persists pending preferences. Process overrides remain effective until restart. */
    void save();

    default void set(ResourceId feature, Option<?> option, Object value) {
        apply(feature, option, value);
        save();
    }

    @Override
    OptionLookup snapshot();
}
