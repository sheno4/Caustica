package dev.comfyfluffy.caustica.minecraft.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Adds a residual-exposed scene-linear ACEScg EXR beside vanilla's ordinary F2 PNG. */
public final class RtScreenshotExporter {
    private RtScreenshotExporter() {
    }

    /**
     * Exports the RT image and returns the exact filename vanilla should use for the paired PNG.
     * Returns {@code null} on export failure so vanilla can use its ordinary auto-naming path.
     */
    public static String exportPaired(File workDir, Consumer<Component> callback) {
        Path screenshotDirectory = workDir.toPath().resolve("screenshots");
        try {
            Files.createDirectories(screenshotDirectory);
            String name = nextPairedName(screenshotDirectory, Util.getFilenameFormattedDateTime());
            Path output = screenshotDirectory.resolve(name + ".exr");
            if (!CausticaClientComposition.current().runtime().exportLatestResidualExposureExr(output)) {
                return name + ".png";
            }
            File file = output.toFile().getAbsoluteFile();
            Component link = Component.literal(file.getName())
                    .withStyle(ChatFormatting.UNDERLINE)
                    .withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(file)));
            callback.accept(Component.literal("Saved residual-exposure ACEScg EXR: ").append(link));
            CausticaMod.LOGGER.info("Saved residual-exposure ACEScg screenshot to {}", file);
            return name + ".png";
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Couldn't save residual-exposure ACEScg screenshot", e);
            callback.accept(Component.literal("Couldn't save Caustica EXR: " + e.getMessage())
                    .withStyle(ChatFormatting.RED));
            return null;
        }
    }

    /** Selects a basename unused by either format; the render-thread caller owns writing the pair. */
    static String nextPairedName(Path directory, String base) {
        int count = 1;
        while (true) {
            String suffix = count == 1 ? "" : "_" + count;
            String name = base + suffix;
            if (!Files.exists(directory.resolve(name + ".exr")) && !Files.exists(directory.resolve(name + ".png"))) {
                return name;
            }
            count++;
        }
    }
}
