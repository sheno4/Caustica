package dev.comfyfluffy.caustica.minecraft;

/** Latest coherent Minecraft frame captured for one world-session epoch. */
public final class MinecraftFrameCaptureState implements MinecraftFrameCaptureInstaller.Sink {
    private volatile MinecraftCapturedFrame current;

    @Override public void update(MinecraftCapturedFrame frame) {
        current = java.util.Objects.requireNonNull(frame, "frame");
    }

    public MinecraftCapturedFrame current() { return current; }
}
