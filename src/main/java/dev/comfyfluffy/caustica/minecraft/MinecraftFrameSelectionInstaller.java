package dev.comfyfluffy.caustica.minecraft;

/** Installs one program epoch's immutable frame selection at the Minecraft client hook boundary. */
@FunctionalInterface
public interface MinecraftFrameSelectionInstaller {
    Lease install(MinecraftFrameSelector selector);

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override void close();
    }
}
