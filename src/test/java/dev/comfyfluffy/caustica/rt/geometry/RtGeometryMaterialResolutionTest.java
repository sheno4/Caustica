package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.MaterialUv;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtGeometryMaterialResolutionTest {
    @Test
    void dynamicAtlasUsesItsUvReferenceThenPairsItsTextureAndCoverage() {
        AtlasMaterialReference reference = new AtlasMaterialReference(ResourceId.of("test", "sprite"),
                ResourceId.of("test", "atlas"), new MaterialUv(0.2f, 0.3f, 4f, 5f));
        List<String> calls = new ArrayList<>();
        RtGeometryMaterialResolver.ResolvedMaterial resolved = RtGeometryMaterialResolution.resolve(
                new SceneMesh.AtlasMaterial(reference), SceneMesh.Coverage.STOCHASTIC, new Bindings(calls));

        assertEquals(List.of("atlas", "texture", "stochastic", "class"), calls);
        assertEquals(111, resolved.bindingId());
    }

    @Test
    void namedDefinitionAndCatalogSelectorsResolveThroughTheirOwnBindings() {
        List<String> calls = new ArrayList<>();
        Bindings bindings = new Bindings(calls);
        MaterialVariant variant = new MaterialVariant(OpenPbrMaterialProfile.CONDUCTOR, MaterialTopology.SURFACE, true);

        RtGeometryMaterialResolution.resolve(new SceneMesh.NamedMaterial(MaterialHandle.of("test", "defined")),
                SceneMesh.Coverage.OPAQUE, bindings);
        RtGeometryMaterialResolution.resolve(new SceneMesh.CatalogMaterial(ResourceId.of("minecraft", "stone"),
                ResourceId.of("test", "geometry"), variant,
                new SceneMesh.StandaloneTexture(ResourceId.of("test", "texture"))),
                SceneMesh.Coverage.CUTOUT, bindings);

        assertEquals(List.of("named", "class", "catalog", "texture", "cutout", "class"), calls);
        assertEquals(ResourceId.of("minecraft", "stone"), bindings.catalogMaterial);
        assertEquals(ResourceId.of("test", "geometry"), bindings.catalogGeometry);
        assertEquals(variant, bindings.catalogVariant);
    }

    private static final class Bindings implements RtGeometryMaterialResolution.Bindings {
        private final List<String> calls;
        private Bindings(List<String> calls) { this.calls = calls; }
        private ResourceId catalogMaterial;
        private ResourceId catalogGeometry;
        private MaterialVariant catalogVariant;
        @Override public int named(MaterialHandle material) { calls.add("named"); return 1; }
        @Override public int catalog(ResourceId material, ResourceId geometry,
                                     MaterialVariant variant) {
            calls.add("catalog");
            catalogMaterial = material;
            catalogGeometry = geometry;
            catalogVariant = variant;
            return 1;
        }
        @Override public int atlas(AtlasMaterialReference reference) { calls.add("atlas"); return 10; }
        @Override public int standalone(ResourceId material) { return 1; }
        @Override public int fallback() { return 1; }
        @Override public int withTexture(int binding, SceneMesh.TextureReference texture) { calls.add("texture"); return binding + 1; }
        @Override public int cutout(int binding) { calls.add("cutout"); return binding + 10; }
        @Override public int stochastic(int binding) { calls.add("stochastic"); return binding + 100; }
        @Override public int sbtClass(int binding) { calls.add("class"); return 0; }
    }
}
