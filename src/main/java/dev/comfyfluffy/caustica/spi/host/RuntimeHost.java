package dev.comfyfluffy.caustica.spi.host;

/**
 * Integration contract implemented by the application hosting the renderer runtime.
 * This SPI controls host lifecycle and authored material semantics; it is not part of the supported extension API.
 */
public interface RuntimeHost {
    void resetPresentationFailure();

    void resetFrameBridge();

    void destroyUiPresentation();

}
