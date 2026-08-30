package dev.comfyfluffy.caustica.minecraft.rendering.light;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;

import java.util.Objects;

/** One terrain light descriptor and the global mesh primitives that shade the same emitter. */
public record MinecraftTerrainEmitter(LightDescriptor.Parallelogram descriptor,
                                      int firstPrimitive, int primitiveCount) {
    public MinecraftTerrainEmitter {
        Objects.requireNonNull(descriptor, "descriptor");
        if (firstPrimitive < 0 || primitiveCount <= 0) {
            throw new IllegalArgumentException("terrain emitter range must be non-empty");
        }
    }
}
