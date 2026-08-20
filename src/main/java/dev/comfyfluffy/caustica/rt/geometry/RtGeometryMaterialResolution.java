package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;

/** Applies renderer material bindings to one neutral triangle surface. */
public final class RtGeometryMaterialResolution {
    private RtGeometryMaterialResolution() { }

    public interface Bindings {
        int named(MaterialHandle material);
        int fallback();
        int textureSlot(SceneMesh.TextureReference texture);
        int sbtClass(int binding);
    }

    public static RtGeometryMaterialResolver.ResolvedMaterial resolve(SceneMesh.MaterialReference material,
                                                                       SceneMesh.Coverage coverage,
                                                                       Bindings bindings) {
        int binding = switch (material) {
            case SceneMesh.NamedMaterial named -> bindings.named(named.material());
            case SceneMesh.FallbackMaterial ignored -> bindings.fallback();
        };
        boolean textured = material.texture() != null;
        int textureSlot = textured ? bindings.textureSlot(material.texture()) : 0;
        int sbtClass = coverage == SceneMesh.Coverage.OPAQUE
                ? bindings.sbtClass(binding) : dev.comfyfluffy.caustica.rt.accel.RtAccel.CLASS_MASKED;
        return new RtGeometryMaterialResolver.ResolvedMaterial(binding, sbtClass,
                PrimitiveMaterialAbi.pack(textureSlot, coverage, textured));
    }
}
