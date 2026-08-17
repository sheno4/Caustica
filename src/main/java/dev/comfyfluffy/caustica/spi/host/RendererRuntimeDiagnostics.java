package dev.comfyfluffy.caustica.spi.host;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Optional host diagnostics backed by the current renderer activation.
 * This first-party integration SPI is not part of the extension API.
 */
public interface RendererRuntimeDiagnostics {
    /** A ready-to-display exposure line, or {@code null} when no current value is available. */
    String exposureSummary();

    boolean exportLatestResidualExposureExr(Path outputPath) throws IOException;
}
