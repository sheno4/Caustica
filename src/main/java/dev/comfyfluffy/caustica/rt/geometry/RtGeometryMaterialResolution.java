package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;

/** Applies renderer material bindings to one neutral triangle surface. */
public final class RtGeometryMaterialResolution {
    private RtGeometryMaterialResolution() { }

    public interface Bindings {
        int named(MaterialHandle material);
        int catalog(ResourceId material, ResourceId geometry, MaterialVariant variant);
        int atlas(AtlasMaterialReference reference);
        int standalone(ResourceId material);
        int fallback();
        int withTexture(int binding, SceneMesh.TextureReference texture);
        int cutout(int binding);
        int stochastic(int binding);
        int sbtClass(int binding);
    }

    public static RtGeometryMaterialResolver.ResolvedMaterial resolve(SceneMesh.MaterialReference material,
                                                                       SceneMesh.Coverage coverage,
                                                                       Bindings bindings) {
        int binding = switch (material) {
            case SceneMesh.NamedMaterial named -> bindings.named(named.material());
            case SceneMesh.CatalogMaterial catalog -> bindings.catalog(catalog.material(), catalog.geometry(), catalog.variant());
            case SceneMesh.AtlasMaterial atlas -> bindings.atlas(atlas.reference());
            case SceneMesh.StandaloneMaterial standalone -> bindings.standalone(standalone.material());
            case SceneMesh.FallbackMaterial ignored -> bindings.fallback();
        };
        if (material.texture() != null) binding = bindings.withTexture(binding, material.texture());
        binding = switch (coverage) {
            case OPAQUE -> binding;
            case CUTOUT -> bindings.cutout(binding);
            case STOCHASTIC -> bindings.stochastic(binding);
        };
        return new RtGeometryMaterialResolver.ResolvedMaterial(binding, bindings.sbtClass(binding));
    }
}
