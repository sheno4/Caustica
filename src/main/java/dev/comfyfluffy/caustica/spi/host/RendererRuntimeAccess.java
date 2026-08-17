package dev.comfyfluffy.caustica.spi.host;

/**
 * Process binding between first-party host adapters and the renderer implementation.
 * This integration SPI is not part of the third-party extension API.
 */
public final class RendererRuntimeAccess {
    private static Binding binding;

    private RendererRuntimeAccess() {
    }

    public static void install(RendererRuntimeController installedController,
                               RendererRuntimeDiagnostics installedDiagnostics,
                               RendererPresentation installedPresentation,
                               HostTelemetry installedTelemetry) {
        binding = new Binding(installedController, installedDiagnostics, installedPresentation, installedTelemetry);
    }

    public static RendererRuntimeController controller() {
        return binding.controller();
    }

    public static RendererRuntimeStatus status() {
        return binding.controller().status();
    }

    public static RendererRuntimeDiagnostics diagnostics() {
        return binding.diagnostics();
    }

    public static RendererPresentation presentation() {
        return binding.presentation();
    }

    public static HostTelemetry telemetry() {
        return binding.telemetry();
    }

    private record Binding(RendererRuntimeController controller,
                           RendererRuntimeDiagnostics diagnostics,
                           RendererPresentation presentation,
                           HostTelemetry telemetry) {
    }
}
