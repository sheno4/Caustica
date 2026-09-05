package dev.comfyfluffy.caustica.api.session;

import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;

/** Owner-scoped services for one contribution in a render session. Ready meshes may be shared. */
public interface RenderSessionContext {
    GpuDevice gpu();

    GpuComputeQueue compute();

    ProgramChannel program();

    PassChannel passes();

    MeshPreparer meshes();

    SceneChannel scene();

    ResourceFactory resources();
}
