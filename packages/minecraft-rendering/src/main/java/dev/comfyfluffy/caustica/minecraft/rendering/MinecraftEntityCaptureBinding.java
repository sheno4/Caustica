package dev.comfyfluffy.caustica.minecraft.rendering;

import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityGeometry;

/** Installs the retained entity owner into the client capture session for one program epoch. */
public interface MinecraftEntityCaptureBinding {
    Lease install(MinecraftEntityGeometry geometry);
    interface Lease extends AutoCloseable { @Override void close(); }
}
