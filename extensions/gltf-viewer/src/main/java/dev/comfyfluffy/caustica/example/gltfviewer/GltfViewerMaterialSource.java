package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;

/** Owns the viewer asset snapshot for each resource epoch and publishes its authored materials. */
final class GltfViewerMaterialSource implements MaterialSource {
    private final GltfViewerAssetRepository assets;

    GltfViewerMaterialSource(GltfViewerAssetRepository assets) {
        this.assets = java.util.Objects.requireNonNull(assets, "assets");
    }

    GltfViewerAssetRepository assets() {
        return assets;
    }

    @Override
    public void submitMaterials(MaterialSink sink) {
        assets.current().materials().forEach(sink::define);
    }

    @Override
    public void onResourcePackClosing() {
        assets.clear();
    }

    @Override
    public void onResourcePackApplied() {
        assets.reload();
    }
}
