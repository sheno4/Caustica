package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtGeometryMaterialResolutionTest {
    @Test
    void namedDefinitionPairsProviderTextureAndCoverage() {
        List<String> calls = new ArrayList<>();
        var material = new SceneMesh.NamedMaterial(MaterialHandle.of("test", "defined"),
                new SceneMesh.StandaloneTexture(ResourceId.of("test", "texture")));

        RtGeometryMaterialResolver.ResolvedMaterial resolved = RtGeometryMaterialResolution.resolve(
                material, SceneMesh.Coverage.CUTOUT, new Bindings(calls));

        assertEquals(List.of("named", "texture"), calls);
        assertEquals(1, resolved.bindingId());
        assertEquals(23, PrimitiveMaterialAbi.textureSlot(resolved.primitiveMaterial()));
        assertEquals(PrimitiveMaterialAbi.COVERAGE_CUTOUT,
                PrimitiveMaterialAbi.coverage(resolved.primitiveMaterial()));
        assertEquals(dev.comfyfluffy.caustica.rt.accel.RtAccel.CLASS_MASKED, resolved.sbtClass());
    }

    @Test
    void textureAndCoverageDoNotCreateNewDefinitionBindings() {
        var material = MaterialHandle.of("test", "defined");
        var first = new SceneMesh.NamedMaterial(material,
                new SceneMesh.StandaloneTexture(ResourceId.of("test", "first")));
        var second = new SceneMesh.NamedMaterial(material,
                new SceneMesh.StandaloneTexture(ResourceId.of("test", "second")));
        Bindings bindings = new Bindings(new ArrayList<>());

        var opaque = RtGeometryMaterialResolution.resolve(first, SceneMesh.Coverage.OPAQUE, bindings);
        var stochastic = RtGeometryMaterialResolution.resolve(second, SceneMesh.Coverage.STOCHASTIC, bindings);

        assertEquals(opaque.bindingId(), stochastic.bindingId());
        assertEquals(23, PrimitiveMaterialAbi.textureSlot(opaque.primitiveMaterial()));
        assertEquals(PrimitiveMaterialAbi.COVERAGE_OPAQUE,
                PrimitiveMaterialAbi.coverage(opaque.primitiveMaterial()));
        assertEquals(PrimitiveMaterialAbi.COVERAGE_STOCHASTIC,
                PrimitiveMaterialAbi.coverage(stochastic.primitiveMaterial()));
    }

    private record Bindings(List<String> calls) implements RtGeometryMaterialResolution.Bindings {
        @Override public int named(MaterialHandle material) { calls.add("named"); return 1; }
        @Override public int fallback() { calls.add("fallback"); return 1; }
        @Override public int textureSlot(SceneMesh.TextureReference texture) { calls.add("texture"); return 23; }
        @Override public int sbtClass(int binding) { calls.add("class"); return 0; }
    }
}
