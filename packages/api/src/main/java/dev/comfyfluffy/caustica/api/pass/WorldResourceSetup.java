package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;

import java.util.Objects;

/** Immutable setup for a world-resource pass. */
public record WorldResourceSetup(GpuDevice gpu) implements PassSetup {
    public WorldResourceSetup {
        Objects.requireNonNull(gpu, "gpu");
    }
}
