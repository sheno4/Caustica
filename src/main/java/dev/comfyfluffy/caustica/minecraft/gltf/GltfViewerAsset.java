package dev.comfyfluffy.caustica.minecraft.gltf;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureAsset;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureKind;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.MaterialUv;
import dev.comfyfluffy.caustica.api.provider.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.color.ColorTransforms;
import dev.comfyfluffy.caustica.gltf.GltfLoader;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adapts a glTF asset without altering primitive vertices or authored scene transforms. */
final class GltfViewerAsset {
    private static final ResourceId DEFAULT_MATERIAL = ResourceId.of("caustica", "gltf_viewer/material_default");

    private GltfViewerAsset() {
    }

    static GltfViewerScene adapt(GltfLoader.Asset asset) {
        List<GltfMaterialTextureSource.ImageData> images = decodeImages(asset.images());
        List<GltfViewerScene.Texture> textures = new ArrayList<>();
        Map<Integer, SceneMesh.TextureReference> baseTextures = new HashMap<>();
        List<MaterialDefinition> definitions = new ArrayList<>();
        List<AdaptedMaterial> materials = new ArrayList<>();
        for (int materialIndex = 0; materialIndex < asset.materials().size(); materialIndex++) {
            AdaptedMaterial adapted = adaptMaterial(asset, images, materialIndex, textures, baseTextures);
            materials.add(adapted);
            definitions.add(adapted.definition());
        }
        MaterialDefinition defaultDefinition = definition(new MaterialHandle(DEFAULT_MATERIAL),
                defaultMaterial(), null);
        definitions.add(defaultDefinition);

        List<GltfViewerScene.Resident> residents = new ArrayList<>();
        SceneGeometryKey[][] residentKeys = new SceneGeometryKey[asset.meshes().size()][];
        long nextResidentKey = 0L;
        for (int meshIndex = 0; meshIndex < asset.meshes().size(); meshIndex++) {
            GltfLoader.Mesh mesh = asset.meshes().get(meshIndex);
            residentKeys[meshIndex] = new SceneGeometryKey[mesh.primitives().size()];
            for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                GltfLoader.Primitive primitive = mesh.primitives().get(primitiveIndex);
                SceneGeometryKey key = SceneGeometryKey.of(nextResidentKey++);
                residentKeys[meshIndex][primitiveIndex] = key;
                AdaptedMaterial material = primitive.material() < 0
                        ? new AdaptedMaterial(defaultDefinition, null, SceneMesh.Coverage.OPAQUE, 1.0f)
                        : materials.get(primitive.material());
                residents.add(new GltfViewerScene.Resident(key, mesh(primitive, material)));
            }
        }

        List<GltfViewerScene.Placement> placements = new ArrayList<>();
        float[] identity = identity();
        for (int root : asset.scene().roots()) {
            collectPlacements(asset, root, identity, residentKeys, placements);
        }
        return new GltfViewerScene(residents, placements, definitions, textures);
    }

    private static AdaptedMaterial adaptMaterial(GltfLoader.Asset asset,
                                                  List<GltfMaterialTextureSource.ImageData> images,
                                                  int materialIndex,
                                                  List<GltfViewerScene.Texture> submittedTextures,
                                                  Map<Integer, SceneMesh.TextureReference> baseTextures) {
        GltfLoader.Material source = asset.materials().get(materialIndex);
        validateTextureInfo(source.baseColorTexture(), "base-color");
        validateTextureInfo(source.metallicRoughnessTexture(), "metallic-roughness");
        validateTextureInfo(source.normalTexture(), "normal");
        validateTextureInfo(source.emissiveTexture(), "emissive");
        SceneMesh.TextureReference baseTexture = null;
        if (source.baseColorTexture() != null) {
            int textureIndex = source.baseColorTexture().texture();
            baseTexture = baseTextures.get(textureIndex);
            if (baseTexture == null) {
                baseTexture = new SceneMesh.StandaloneTexture(ResourceId.of(
                        "caustica", "gltf_viewer/texture_" + textureIndex));
                GltfMaterialTextureSource.ImageData image = textureImage(asset, images, textureIndex);
                submittedTextures.add(new GltfViewerScene.Texture(baseTexture, cpuTexture(image)));
                baseTextures.put(textureIndex, baseTexture);
            }
        }

        GltfMaterialTextureSource.ImageData mrImage = textureImage(asset, images, source.metallicRoughnessTexture());
        GltfMaterialTextureSource.ImageData normalImage = textureImage(asset, images, source.normalTexture());
        GltfMaterialTextureSource.ImageData emissiveImage = textureImage(asset, images, source.emissiveTexture());
        MaterialTextureAsset semanticTextures = null;
        if (mrImage != null || normalImage != null || emissiveImage != null) {
            int width = 1;
            int height = 1;
            for (GltfMaterialTextureSource.ImageData image :
                    new GltfMaterialTextureSource.ImageData[]{mrImage, normalImage, emissiveImage}) {
                if (image != null) {
                    width = Math.max(width, image.width());
                    height = Math.max(height, image.height());
                }
            }
            MaterialHandle handle = materialHandle(materialIndex);
            semanticTextures = new MaterialTextureAsset(handle.id(), MaterialTextureKind.STANDALONE,
                    width, height,
                    new GltfMaterialTextureSource(mrImage, normalImage, emissiveImage,
                            width, height, source.ior(),
                            source.normalTexture() == null ? 1.0f : source.normalTexture().scale()),
                    MaterialUv.IDENTITY,
                    mrImage != null, normalImage != null, emissiveImage != null,
                    OpenPbrColorBinding.PARAMETER_DEFAULT,
                    OpenPbrColorBinding.PARAMETER_DEFAULT,
                    source.ior(), 0.0f);
        }
        MaterialHandle handle = materialHandle(materialIndex);
        MaterialDefinition definition = definition(handle, source, semanticTextures);
        SceneMesh.Coverage coverage = switch (source.alphaMode()) {
            case OPAQUE -> SceneMesh.Coverage.OPAQUE;
            case MASK -> SceneMesh.Coverage.CUTOUT;
            case BLEND -> SceneMesh.Coverage.STOCHASTIC;
        };
        return new AdaptedMaterial(definition, baseTexture, coverage, source.baseColorA());
    }

    private static MaterialDefinition definition(MaterialHandle handle, GltfLoader.Material source,
                                                 MaterialTextureAsset textures) {
        float[] baseColor = ColorTransforms.linearBt709ToAcesCg(
                source.baseColorR(), source.baseColorG(), source.baseColorB());
        float[] emissionColor = ColorTransforms.linearBt709ToAcesCg(
                source.emissiveR(), source.emissiveG(), source.emissiveB());
        float emissionLuminance = source.emissiveR() == 0.0f
                && source.emissiveG() == 0.0f && source.emissiveB() == 0.0f
                ? 0.0f
                : Math.min(65504.0f, MinecraftLightingCalibration.current()
                        .blockEmissionLuminanceCdM2() * source.emissiveStrength());
        return new MaterialDefinition(handle,
                baseColor[0], baseColor[1], baseColor[2],
                source.roughness(), source.metallic(), source.ior(), source.transmissionFactor(),
                1.0f, 1.0f, 1.0f,
                0.0f, 0.8f, 0.8f, 0.8f, 0.0f,
                emissionColor[0], emissionColor[1], emissionColor[2], emissionLuminance,
                MaterialTopology.SURFACE, null, source.alphaCutoff(), textures);
    }

    private static SceneMesh mesh(GltfLoader.Primitive primitive, AdaptedMaterial material) {
        int vertexCount = primitive.positions().length / 3;
        int[] indices = nonDegenerateIndices(primitive.positions(), primitive.indices());
        float[] uvs = primitive.textureCoordinates();
        boolean textured = material.baseTexture() != null || material.definition().textures() != null;
        if (textured && uvs.length == 0) {
            throw new IllegalArgumentException("textured glTF primitive has no TEXCOORD_0");
        }
        if (uvs.length == 0) {
            uvs = new float[vertexCount * 2];
        }
        float[] colors = primitive.colors();
        if (material.alphaFactor() != 1.0f) {
            if (colors.length == 0) {
                colors = new float[vertexCount * 4];
                Arrays.fill(colors, 1.0f);
            } else {
                colors = colors.clone();
            }
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                colors[vertex * 4 + 3] *= material.alphaFactor();
            }
        }
        SceneMesh.NamedMaterial reference = new SceneMesh.NamedMaterial(
                material.definition().handle(), material.baseTexture());
        SceneMesh.TriangleSurface surface = new SceneMesh.TriangleSurface(reference,
                material.coverage(), Float.NaN, Float.NaN, Float.NaN, 0.0f, 1.0f, 1.0f, 1.0f);
        return new SceneMesh(primitive.positions(), indices, SceneMesh.UvLayout.PER_VERTEX,
                uvs, primitive.normals(), colors,
                Collections.nCopies(indices.length / 3, surface), Set.of());
    }

    private static int[] nonDegenerateIndices(float[] positions, int[] indices) {
        int[] filtered = new int[indices.length];
        int count = 0;
        for (int offset = 0; offset < indices.length; offset += 3) {
            int a = indices[offset] * 3;
            int b = indices[offset + 1] * 3;
            int c = indices[offset + 2] * 3;
            float abx = positions[b] - positions[a];
            float aby = positions[b + 1] - positions[a + 1];
            float abz = positions[b + 2] - positions[a + 2];
            float acx = positions[c] - positions[a];
            float acy = positions[c + 1] - positions[a + 1];
            float acz = positions[c + 2] - positions[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            if (nx * nx + ny * ny + nz * nz > 1.0e-16f) {
                filtered[count++] = indices[offset];
                filtered[count++] = indices[offset + 1];
                filtered[count++] = indices[offset + 2];
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("glTF primitive has no non-degenerate triangles");
        }
        return count == filtered.length ? filtered : Arrays.copyOf(filtered, count);
    }

    private static void collectPlacements(GltfLoader.Asset asset, int nodeIndex, float[] parentWorld,
                                          SceneGeometryKey[][] residentKeys,
                                          List<GltfViewerScene.Placement> placements) {
        GltfLoader.Node node = asset.nodes().get(nodeIndex);
        float[] world = multiply(parentWorld, node.localMatrix());
        if (node.mesh() >= 0) {
            for (SceneGeometryKey resident : residentKeys[node.mesh()]) {
                placements.add(new GltfViewerScene.Placement(resident, world));
            }
        }
        for (int child : node.children()) {
            collectPlacements(asset, child, world, residentKeys, placements);
        }
    }

    private static List<GltfMaterialTextureSource.ImageData> decodeImages(List<GltfLoader.Image> images) {
        List<GltfMaterialTextureSource.ImageData> decoded = new ArrayList<>(images.size());
        for (GltfLoader.Image image : images) {
            try (NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(image.encoded()))) {
                int width = nativeImage.getWidth();
                int height = nativeImage.getHeight();
                int[] argb = new int[Math.multiplyExact(width, height)];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        argb[y * width + x] = nativeImage.getPixel(x, y);
                    }
                }
                decoded.add(new GltfMaterialTextureSource.ImageData(width, height, argb));
            } catch (IOException exception) {
                throw new IllegalArgumentException("failed to decode glTF image " + image.name(), exception);
            }
        }
        return List.copyOf(decoded);
    }

    private static GltfMaterialTextureSource.ImageData textureImage(
            GltfLoader.Asset asset, List<GltfMaterialTextureSource.ImageData> images,
            GltfLoader.TextureInfo info) {
        return info == null ? null : textureImage(asset, images, info.texture());
    }

    private static GltfMaterialTextureSource.ImageData textureImage(
            GltfLoader.Asset asset, List<GltfMaterialTextureSource.ImageData> images, int textureIndex) {
        GltfLoader.Texture texture = asset.textures().get(textureIndex);
        requireSupportedSampler(texture.sampler());
        return images.get(texture.image());
    }

    private static void validateTextureInfo(GltfLoader.TextureInfo info, String semantic) {
        if (info != null && info.texCoord() != 0) {
            throw new IllegalArgumentException(semantic + " texture uses unsupported TEXCOORD_" + info.texCoord());
        }
    }

    private static void requireSupportedSampler(GltfLoader.Sampler sampler) {
        if (sampler.wrapS() != GltfLoader.Wrap.REPEAT || sampler.wrapT() != GltfLoader.Wrap.REPEAT) {
            throw new IllegalArgumentException("glTF viewer currently requires REPEAT texture wrapping");
        }
    }

    private static CpuTextureResource cpuTexture(GltfMaterialTextureSource.ImageData image) {
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(image.width(), image.height()), 4)];
        int[] argb = image.argb();
        for (int pixelIndex = 0; pixelIndex < argb.length; pixelIndex++) {
            int pixel = argb[pixelIndex];
            int byteIndex = pixelIndex * 4;
            rgba[byteIndex] = (byte) (pixel >>> 16);
            rgba[byteIndex + 1] = (byte) (pixel >>> 8);
            rgba[byteIndex + 2] = (byte) pixel;
            rgba[byteIndex + 3] = (byte) (pixel >>> 24);
        }
        return new CpuTextureResource(image.width(), image.height(), CpuTextureResource.Encoding.SRGB, rgba);
    }

    private static MaterialHandle materialHandle(int index) {
        return new MaterialHandle(ResourceId.of("caustica", "gltf_viewer/material_" + index));
    }

    private static GltfLoader.Material defaultMaterial() {
        return new GltfLoader.Material("default", 1, 1, 1, 1, null,
                1, 1, null, null, 0, 0, 0, null,
                GltfLoader.AlphaMode.OPAQUE, 0.5f, false, 1.5f, 0, 1);
    }

    private static float[] identity() {
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0.0f;
                for (int k = 0; k < 4; k++) {
                    value += left[k * 4 + row] * right[column * 4 + k];
                }
                result[column * 4 + row] = value;
            }
        }
        return result;
    }

    private record AdaptedMaterial(MaterialDefinition definition,
                                   SceneMesh.TextureReference baseTexture,
                                   SceneMesh.Coverage coverage, float alphaFactor) {
    }
}
