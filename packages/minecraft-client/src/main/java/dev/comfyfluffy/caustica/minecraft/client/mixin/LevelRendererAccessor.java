package dev.comfyfluffy.caustica.minecraft.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The transient gizmo stream is consumed even when RT replaces vanilla raster work. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("renderThreadGizmos")
    SimpleGizmoCollector caustica$getRenderThreadGizmos();
}
