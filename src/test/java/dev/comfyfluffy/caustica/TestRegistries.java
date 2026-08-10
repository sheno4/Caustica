package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;

public final class TestRegistries {
    private TestRegistries() {
    }

    public static CausticaRegistry withBuiltins() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new MinecraftProvidersExtension().register(registry);
        return registry;
    }
}
