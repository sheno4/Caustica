package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;

/**
 * Immutable services shared by every pass created for one render session.
 *
 * @param gpu session GPU services
 */
public record PassSetup(GpuDevice gpu) {
    public PassSetup {
        if (gpu == null) throw new NullPointerException();
    }
}
