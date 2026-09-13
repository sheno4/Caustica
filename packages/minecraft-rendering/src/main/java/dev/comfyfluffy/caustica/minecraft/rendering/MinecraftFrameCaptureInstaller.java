package dev.comfyfluffy.caustica.minecraft.rendering;

import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SpatialMedium;

/** Installs one world session's captured-frame consumer and immutable lighting calibration at the client hook. */
@FunctionalInterface
public interface MinecraftFrameCaptureInstaller {
    Lease install(Sink sink, MinecraftLightingCalibration calibration);

    @FunctionalInterface
    interface Sink {
        void update(MinecraftCapturedFrame frame);

        /** Borrows a medium whose data remains valid until the engine captures this frame. */
        default SpatialMedium<?, ?> spatialMedium(Camera camera, double originX, double originY,
                                                 double originZ, double metersPerSceneUnit) {
            return null;
        }
    }

    @FunctionalInterface
    interface Lease extends AutoCloseable { @Override void close(); }
}
