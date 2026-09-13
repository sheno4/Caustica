package dev.comfyfluffy.caustica.minecraft.rendering;

/** Latest coherent Minecraft frame captured for one world-session epoch. */
public final class MinecraftFrameCaptureState implements MinecraftFrameCaptureInstaller.Sink {
    private volatile MinecraftCapturedFrame current;

    @Override public void update(MinecraftCapturedFrame frame) {
        current = java.util.Objects.requireNonNull(frame, "frame");
    }

    public MinecraftCapturedFrame current() { return current; }

    public MinecraftFogFrame fogFrame() {
        MinecraftCapturedFrame frame = current;
        return frame == null ? null : frame.fog().orElse(null);
    }

    public MinecraftLightFrame lightFrame() {
        MinecraftCapturedFrame frame = current;
        return frame == null ? null : frame.light();
    }

    public MinecraftSkyFrame skyFrame() {
        MinecraftCapturedFrame frame = current;
        return frame == null ? null : frame.sky().orElse(null);
    }
}
