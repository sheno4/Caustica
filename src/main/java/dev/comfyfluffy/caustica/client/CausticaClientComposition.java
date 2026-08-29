package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.minecraft.MinecraftApiBootstrap;
import dev.comfyfluffy.caustica.rt.RtRuntime;

import java.util.Objects;

/** Process-owned client services published for loader and mixin hooks after common initialization. */
public final class CausticaClientComposition {
    private static CausticaClientComposition current;

    private final RtRuntime runtime;
    private final MinecraftApiBootstrap.ApiServices apiServices;

    public CausticaClientComposition(RtRuntime runtime, MinecraftApiBootstrap.ApiServices apiServices) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.apiServices = Objects.requireNonNull(apiServices, "apiServices");
    }

    public RtRuntime runtime() {
        return runtime;
    }

    public MinecraftApiBootstrap.ApiServices apiServices() {
        return apiServices;
    }

    public static synchronized void publish(CausticaClientComposition composition) {
        if (current != null) {
            throw new IllegalStateException("Caustica client composition is already published");
        }
        current = Objects.requireNonNull(composition, "composition");
    }

    public static synchronized CausticaClientComposition current() {
        if (current == null) {
            throw new IllegalStateException("Caustica client composition is not published");
        }
        return current;
    }
}
