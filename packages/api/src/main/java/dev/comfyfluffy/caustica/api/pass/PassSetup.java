package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;

/** Services present in the immutable setup for every pass stage in one render session. */
public interface PassSetup {
    /** Session GPU services. */
    GpuDevice gpu();
}
