package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;

/** Owns the viewer asset snapshot for each resource epoch and publishes its authored materials. */
final class GltfViewerMaterialSource implements MaterialSource {
    static final int METALLIC_ROUGHNESS_PRESENT = 1;
    static final int NORMAL_PRESENT = 2;
    static final int EMISSIVE_PRESENT = 4;

    private final GltfViewerAssetRepository assets;

    GltfViewerMaterialSource(GltfViewerAssetRepository assets) {
        this.assets = java.util.Objects.requireNonNull(assets, "assets");
    }

    GltfViewerAssetRepository assets() {
        return assets;
    }

    @Override
    public void submitMaterials(MaterialSink sink) {
        for (GltfViewerScene.Material material : assets.current().materials()) {
            int flags = 0;
            int metallicRoughness = 0;
            int normal = 0;
            int emissive = 0;
            if (material.metallicRoughness() != null) {
                flags |= METALLIC_ROUGHNESS_PRESENT;
                metallicRoughness = sink.register(material.metallicRoughness());
            }
            if (material.normal() != null) {
                flags |= NORMAL_PRESENT;
                normal = sink.register(material.normal());
            }
            if (material.emissive() != null) {
                flags |= EMISSIVE_PRESENT;
                emissive = sink.register(material.emissive());
            }
            int[] words = new int[MaterialProviderData.WORD_COUNT];
            words[0] = flags;
            words[1] = metallicRoughness;
            words[2] = normal;
            words[3] = emissive;
            words[4] = Float.floatToRawIntBits(material.normalScale());
            sink.define(withProviderData(material.definition(), new MaterialProviderData(words)));
        }
    }

    private static MaterialDefinition withProviderData(
            MaterialDefinition source, MaterialProviderData providerData) {
        return new MaterialDefinition(source.handle(),
                source.baseColorR(), source.baseColorG(), source.baseColorB(),
                source.specularRoughness(), source.baseMetalness(), source.specularIor(),
                source.transmissionWeight(),
                source.transmissionColorR(), source.transmissionColorG(), source.transmissionColorB(),
                source.subsurfaceWeight(),
                source.subsurfaceColorR(), source.subsurfaceColorG(), source.subsurfaceColorB(),
                source.subsurfaceScatterAnisotropy(),
                source.emissionColorR(), source.emissionColorG(), source.emissionColorB(),
                source.emissionLuminanceCdM2(), source.topology(), source.surface(),
                source.alphaCutoff(), providerData);
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
