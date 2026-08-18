package dev.comfyfluffy.caustica.minecraft.gltf;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.RuntimeActivation;

/** Reference extension that instances an authored glTF scene from invisible world anchors. */
public final class GltfViewerExtension implements CausticaExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "gltf_viewer");
    public static final ResourceId MATERIAL_SOURCE = ResourceId.of("caustica", "gltf_viewer_materials");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.literal("glTF Viewer"))
                .description(DisplayText.literal("Instances an authored glTF scene at viewer anchors."))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .sceneProvider(GltfViewerSceneProvider.ID, GltfViewerSceneProvider::new)
                .materialSource(MATERIAL_SOURCE, () -> sink ->
                        GltfViewerSceneProvider.model().materials().forEach(sink::define))
                .register();
    }
}
