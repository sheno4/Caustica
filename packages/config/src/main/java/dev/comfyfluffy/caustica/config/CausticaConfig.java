package dev.comfyfluffy.caustica.config;

import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;

/** Access to the process settings store installed by the host before runtime construction. */
public final class CausticaConfig {
    public static final ResourceId FEATURE = new ResourceId("caustica", "renderer");
    private static CausticaOptions store;
    private CausticaConfig() { }

    public static void install(CausticaOptions options) { store = options; }
    public static CausticaOptions store() { return store; }
    public static OptionValues snapshot() { return store.snapshot().options(FEATURE); }
    public static <T> T get(Option<T> option) { return snapshot().get(option); }
}
