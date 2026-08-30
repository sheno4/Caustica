package dev.comfyfluffy.caustica.minecraft.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to the per-frame bookkeeping that must still run when the RT renderer cancels vanilla raster work. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Invoker("repositionCamera")
    void caustica$repositionCamera(CameraRenderState cameraState);

    @Accessor("renderThreadGizmos")
    SimpleGizmoCollector caustica$getRenderThreadGizmos();
}
