package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.RuntimeActivation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals(GltfViewerExtension.PROCEDURAL_SURFACE, feature.surfaces().getFirst().id());
    }
}
