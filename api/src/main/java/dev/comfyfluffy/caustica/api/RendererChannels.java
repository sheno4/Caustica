package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;

/**
 * The renderer's channels, installed once by the host adapter. Extensions reach them through
 * {@link CausticaApi} and never name this type.
 *
 * <p>Each channel is process-lived while the collection behind it is not: it answers for whichever render
 * session is current, drops submissions when none is, and reports that through its own generation.
 */
public interface RendererChannels {
    GpuDevice gpu();

    ProgramChannel program();

    PassChannel passes();

    SceneChannel scenes();

    GeometryChannel geometry();

    MaterialChannel materials();

    LightChannel lights();
}
