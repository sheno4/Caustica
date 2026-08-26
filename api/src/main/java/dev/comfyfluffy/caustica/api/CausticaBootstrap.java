package dev.comfyfluffy.caustica.api;

/** Host-adapter bootstrap for installing the process-wide extension API. */
public final class CausticaBootstrap {
    private CausticaBootstrap() {
    }

    /** Install the renderer channels before invoking any {@link CausticaExtension}. */
    public static void install(RendererChannels channels) {
        CausticaApi.install(channels);
    }
}
