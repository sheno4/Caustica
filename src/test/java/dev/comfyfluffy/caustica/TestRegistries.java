package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;

public final class TestRegistries {
    private TestRegistries() {
    }

    public static CausticaRegistry withBuiltins() {
        CausticaRegistry registry = rendererOnly();
        new MinecraftProvidersExtension().register(registry);
        return registry;
    }

    /** Only the renderer's own extension, for asserting what a host contributes on top of it. */
    public static CausticaRegistry rendererOnly() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        return registry;
    }
}
