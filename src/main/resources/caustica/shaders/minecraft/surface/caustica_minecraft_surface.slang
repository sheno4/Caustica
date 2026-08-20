module caustica_minecraft_surface;

import caustica_surface;
import caustica_types;
import caustica_minecraft_material;

/** Minecraft's base-texture and LabPBR material adapter. */
public struct MinecraftSurface : ISurfaceModel {
    public override void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        evaluateMinecraftMaterial(input, material);
    }
};
