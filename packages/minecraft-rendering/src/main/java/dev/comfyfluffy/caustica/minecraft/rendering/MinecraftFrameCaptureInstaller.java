package dev.comfyfluffy.caustica.minecraft.rendering;

/** Installs one world session's captured-frame consumer and immutable lighting calibration at the client hook. */
@FunctionalInterface
public interface MinecraftFrameCaptureInstaller {
    Lease install(Sink sink, MinecraftLightingCalibration calibration);

    @FunctionalInterface
    interface Sink { void update(MinecraftCapturedFrame frame); }

    @FunctionalInterface
    interface Lease extends AutoCloseable { @Override void close(); }
}
