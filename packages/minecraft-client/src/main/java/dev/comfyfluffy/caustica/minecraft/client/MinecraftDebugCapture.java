package dev.comfyfluffy.caustica.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Provides an opt-in file channel for window-independent screenshot requests. */
public final class MinecraftDebugCapture {
    static final String REQUEST_PROPERTY = "caustica.debug.captureRequest";
    static final String STOP_AFTER_CAPTURE_PROPERTY = "caustica.debug.stopAfterCapture";
    private static final MinecraftDebugCapture INSTANCE = configured();
    private static final boolean STOP_AFTER_CAPTURE = Boolean.getBoolean(STOP_AFTER_CAPTURE_PROPERTY);

    private final Path requestFile;
    private int framesUntilCapture = -1;

    MinecraftDebugCapture(Path requestFile) {
        this.requestFile = requestFile;
    }

    public static void poll(Minecraft minecraft, boolean frameActive) {
        if (!INSTANCE.shouldCapture(frameActive)) {
            return;
        }
        Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(), message -> {
            CausticaMod.LOGGER.info("Debug capture completed: {}", message.getString());
            minecraft.execute(() -> {
                minecraft.showDebugChat(message);
                if (STOP_AFTER_CAPTURE) {
                    CausticaMod.LOGGER.info("Stopping client after requested debug capture");
                    minecraft.stop();
                }
            });
        });
    }

    boolean shouldCapture(boolean frameActive) {
        if (requestFile == null || !frameActive) {
            return false;
        }
        if (framesUntilCapture < 0) {
            if (!Files.isRegularFile(requestFile)) {
                return false;
            }
            framesUntilCapture = readDelayFrames();
            try {
                Files.delete(requestFile);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot consume debug capture request " + requestFile, e);
            }
            CausticaMod.LOGGER.info("Debug capture requested after {} active frames", framesUntilCapture);
        }
        if (--framesUntilCapture > 0) {
            return false;
        }
        framesUntilCapture = -1;
        return true;
    }

    private int readDelayFrames() {
        try {
            String value = Files.readString(requestFile, StandardCharsets.UTF_8).trim();
            int frames = value.isEmpty() ? 1 : Integer.parseInt(value);
            if (frames < 1) {
                throw new IllegalArgumentException("Debug capture delay must be at least one frame");
            }
            return frames;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read debug capture request " + requestFile, e);
        }
    }

    private static MinecraftDebugCapture configured() {
        String configured = System.getProperty(REQUEST_PROPERTY);
        return new MinecraftDebugCapture(configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize());
    }
}
