package dev.comfyfluffy.caustica.minecraft;

/** Installs one world session's captured-frame consumer at the Minecraft client hook boundary. */
@FunctionalInterface
public interface MinecraftFrameCaptureInstaller {
    Lease install(Sink sink);

    @FunctionalInterface
    interface Sink { void update(MinecraftCapturedFrame frame); }

    @FunctionalInterface
    interface Lease extends AutoCloseable { @Override void close(); }
}
