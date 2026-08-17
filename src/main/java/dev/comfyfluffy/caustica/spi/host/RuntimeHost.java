package dev.comfyfluffy.caustica.spi.host;

/**
 * Reverse callbacks implemented by the application hosting the renderer runtime.
 * This integration SPI is not part of the supported extension API.
 */
public interface RuntimeHost {
    void resetPresentationFailure();

    void resetFrameBridge();

    void destroyUiPresentation();
}
