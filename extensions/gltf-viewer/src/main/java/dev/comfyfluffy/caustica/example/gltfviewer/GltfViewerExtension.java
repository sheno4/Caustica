package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.FeatureRuntimeContext;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.RuntimeActivation;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;

/** Reference extension that instances an authored glTF scene from invisible world anchors. */
public final class GltfViewerExtension implements CausticaExtension {
    public static final ResourceId ID = ResourceId.of(GltfViewerMod.MOD_ID, "viewer");
    public static final ResourceId MATERIAL_SOURCE = ResourceId.of(GltfViewerMod.MOD_ID, "gltf_materials");
    public static final ResourceId PROCEDURAL_MATERIAL_SOURCE = ResourceId.of(
            GltfViewerMod.MOD_ID, "procedural_materials");
    public static final ResourceId PROCEDURAL_SURFACE = ResourceId.of(GltfViewerMod.MOD_ID, "portal_surface");
    public static final ResourceId PROCEDURAL_MATERIAL = ResourceId.of(
            GltfViewerMod.MOD_ID, "procedural_portal");
    static final FeatureRuntimeContext.Key<GltfViewerAssetRepository> ASSETS =
            new FeatureRuntimeContext.Key<>(ResourceId.of(GltfViewerMod.MOD_ID, "assets"),
                    GltfViewerAssetRepository.class);

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.literal("glTF Viewer"))
                .description(DisplayText.literal(
                        "Instances glTF scenes and an extension-owned procedural surface."))
                .shaderSource(ShaderSource.classpath(GltfViewerExtension.class,
                        "/caustica_gltf_viewer/shaders"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .surface(PROCEDURAL_SURFACE,
                        "caustica_gltf_viewer_portal_surface", "GltfViewerPortalSurface")
                .sceneProviderContextual(GltfViewerSceneProvider.ID, context ->
                        new GltfViewerSceneProvider(assets(context), GltfViewerSceneProvider::loadedAnchors))
                .sceneProvider(ProceduralSurfaceSceneProvider.ID, ProceduralSurfaceSceneProvider::new)
                .materialSourceContextual(MATERIAL_SOURCE, context -> new GltfViewerMaterialSource(assets(context)))
                .materialSource(PROCEDURAL_MATERIAL_SOURCE, () -> sink -> sink.define(
                        new MaterialDefinition(new MaterialHandle(PROCEDURAL_MATERIAL),
                                0.002f, 0.001f, 0.006f,
                                0.42f, 0.0f, 1.35f, 0.0f,
                                MaterialTopology.SURFACE, PROCEDURAL_SURFACE)))
                .register();
    }

    private static GltfViewerAssetRepository assets(FeatureRuntimeContext context) {
        return context.getOrCreate(ASSETS, GltfViewerAssetRepository::new);
    }
}
