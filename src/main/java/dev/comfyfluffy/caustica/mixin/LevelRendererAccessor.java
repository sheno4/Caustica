package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to the section-maintenance methods skipped when the RT renderer cancels vanilla raster work. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Invoker("repositionCamera")
    void caustica$repositionCamera(CameraRenderState cameraState);

    @Invoker("compileSections")
    void caustica$compileSections(CameraRenderState cameraState);
}
