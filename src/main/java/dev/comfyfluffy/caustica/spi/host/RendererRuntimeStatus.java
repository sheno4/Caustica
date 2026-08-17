package dev.comfyfluffy.caustica.spi.host;

/** Immutable renderer state observed by first-party host hooks during one frame. */
public record RendererRuntimeStatus(
        State state,
        boolean frameActive,
        boolean sessionPresent,
        boolean rendererFailed,
        WorldReplacement worldReplacement,
        boolean hdrPresentation,
        boolean pqSdrPresentation,
        boolean pqSwapchainRequested,
        long frameIndex) {

    public enum State {
        OFF,
        STARTING,
        ACTIVE,
        STOPPING,
        FAILED
    }

    public enum WorldReplacement {
        READY,
        FRAME_INACTIVE,
        DEVICE_UNAVAILABLE,
        RENDERER_FAILED,
        RESOURCE_TRANSITION
    }

    public boolean active() {
        return state == State.ACTIVE;
    }
}
