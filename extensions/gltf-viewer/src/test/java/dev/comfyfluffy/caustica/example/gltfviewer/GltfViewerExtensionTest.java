package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.RuntimeActivation;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GltfViewerExtensionTest {
    @Test
    void registersAlwaysActiveSceneAndMaterialProviders() {
        CausticaRegistry registry = new CausticaRegistry();

        new GltfViewerExtension().register(registry);

        var feature = registry.features().get(GltfViewerExtension.ID);
        assertEquals(RuntimeActivation.ALWAYS, feature.runtimeActivation());
        assertTrue(feature.sceneProviders().stream()
                .anyMatch(provider -> provider.id().equals(GltfViewerSceneProvider.ID)));
        assertTrue(feature.sceneProviders().stream()
                .anyMatch(provider -> provider.id().equals(ProceduralSurfaceSceneProvider.ID)));
        assertTrue(feature.materialSources().stream()
                .anyMatch(provider -> provider.id().equals(GltfViewerExtension.MATERIAL_SOURCE)));
        assertSame(GltfViewerExtension.class, feature.shaderSource().resourceAnchor());
        assertEquals(2, feature.surfaces().size());
        var material = feature.surfaces().getFirst();
        assertEquals(GltfViewerExtension.MATERIAL_SURFACE, material.id());
        assertEquals("caustica_gltf_viewer_material_surface", material.module());
        assertEquals("GltfViewerMaterialSurface", material.type());
        assertEquals(GltfViewerExtension.MATERIAL_COVERAGE, material.coverageId());
        assertEquals("caustica_gltf_viewer_material_coverage", material.coverageModule());
        assertEquals("GltfViewerMaterialCoverage", material.coverageType());
        var portal = feature.surfaces().getLast();
        assertEquals(GltfViewerExtension.PROCEDURAL_SURFACE, portal.id());
        assertEquals(GltfViewerExtension.MATERIAL_COVERAGE, portal.coverageId());
    }

    @Test
    void sceneAndMaterialShareOneRepositoryPerRuntimeActivationWithoutMutableStaticState() {
        CausticaRegistry registry = registryWithDefaultSky();
        new GltfViewerExtension().register(registry);

        var first = registry.createRuntimeContributions();
        var second = registry.createRuntimeContributions();
        GltfViewerSceneProvider firstScene = (GltfViewerSceneProvider) first.sceneProviders()
                .get(GltfViewerSceneProvider.ID);
        GltfViewerMaterialSource firstMaterials = (GltfViewerMaterialSource) first.materialSources()
                .get(GltfViewerExtension.MATERIAL_SOURCE);
        GltfViewerSceneProvider secondScene = (GltfViewerSceneProvider) second.sceneProviders()
                .get(GltfViewerSceneProvider.ID);
        GltfViewerMaterialSource secondMaterials = (GltfViewerMaterialSource) second.materialSources()
                .get(GltfViewerExtension.MATERIAL_SOURCE);

        assertSame(firstScene.assets(), firstMaterials.assets());
        assertSame(secondScene.assets(), secondMaterials.assets());
        assertNotSame(firstScene.assets(), secondScene.assets());
        assertTrue(java.util.Arrays.stream(GltfViewerAssetRepository.class.getDeclaredFields())
                .noneMatch(field -> Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())));
    }

    private static CausticaRegistry registryWithDefaultSky() {
        CausticaRegistry registry = new CausticaRegistry();
        var sky = dev.comfyfluffy.caustica.api.ResourceId.of("test", "sky");
        registry.feature(sky)
                .shaderSource(ShaderSource.classpath("/test/shaders"))
                .bind(Slots.SKY, "test_sky", "TestSky")
                .register();
        registry.setDefault(Slots.SKY, sky);
        return registry;
    }
}
