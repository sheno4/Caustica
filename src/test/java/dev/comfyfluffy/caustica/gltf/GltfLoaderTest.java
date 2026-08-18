package dev.comfyfluffy.caustica.gltf;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;

final class GltfLoaderTest {
  @Test
  void preservesCommittedKhronosVertexColorAsset() throws Exception {
    Path source =
        Path.of(
            getClass()
                .getResource("/assets/caustica/gltf/box_vertex_colors/box_vertex_colors.gltf")
                .toURI());
    GltfLoader.Asset asset = GltfLoader.load(source);

    assertArrayEquals(new int[] {0}, asset.scene().roots());
    assertEquals(1, asset.nodes().size());
    assertEquals(0, asset.nodes().getFirst().mesh());
    assertArrayEquals(identity(), asset.nodes().getFirst().localMatrix(), 0);
    assertEquals(1, asset.meshes().size());
    GltfLoader.Primitive primitive = asset.meshes().getFirst().primitives().getFirst();
    assertEquals(72, primitive.positions().length);
    assertEquals(36, primitive.indices().length);
    assertEquals(72, primitive.normals().length);
    assertEquals(96, primitive.colors().length);
    assertEquals(0, primitive.textureCoordinates().length);
    assertEquals(-1, primitive.material());
    assertEquals(0, asset.materials().size());
  }

  @Test
  void decodesKhronosLanternHierarchyMaterialsAndEmbeddedImages() throws Exception {
    Path source =
        Path.of(getClass().getResource("/assets/caustica/gltf/viewer/model.glb").toURI());
    GltfLoader.Asset asset = GltfLoader.load(source);

    assertEquals(4, asset.nodes().size());
    assertEquals(3, asset.meshes().size());
    assertEquals(1, asset.materials().size());
    assertEquals(4, asset.textures().size());
    assertEquals(4, asset.images().size());
    assertArrayEquals(new int[] {3}, asset.scene().roots());
    assertArrayEquals(new int[] {0, 1, 2}, asset.nodes().get(3).children());
    assertEquals(-1, asset.nodes().get(3).mesh());
    assertTrue(asset.meshes().stream().allMatch(mesh -> mesh.primitives().size() == 1));
    assertTrue(
        asset.meshes().stream().allMatch(mesh -> mesh.primitives().getFirst().material() == 0));
    assertTrue(
        asset.meshes().stream()
            .allMatch(mesh -> mesh.primitives().getFirst().tangents().length > 0));

    GltfLoader.Material material = asset.materials().getFirst();
    assertEquals(0, material.baseColorTexture().texture());
    assertEquals(1, material.metallicRoughnessTexture().texture());
    assertEquals(2, material.normalTexture().texture());
    assertEquals(3, material.emissiveTexture().texture());
    assertEquals(1, material.emissiveR());
    assertTrue(
        asset.images().stream()
            .allMatch(image -> image.mimeType().equals("image/png") && image.encoded().length > 0));
    assertTrue(
        asset.textures().stream()
            .allMatch(
                texture ->
                    texture
                        .sampler()
                        .equals(
                            new GltfLoader.Sampler(
                                GltfLoader.MagFilter.LINEAR,
                                GltfLoader.MinFilter.LINEAR_MIPMAP_LINEAR,
                                GltfLoader.Wrap.REPEAT,
                                GltfLoader.Wrap.REPEAT))));
  }

  @Test
  void preservesSharedMeshLocalTransformsAndExtendedMaterial() throws Exception {
    ByteBuffer bytes = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}) bytes.putFloat(value);
    for (int i = 0; i < 3; i++) for (float value : new float[] {1, 0, 0, 1}) bytes.putFloat(value);
    String data = Base64.getEncoder().encodeToString(bytes.array());
    String json =
        """
        {"asset":{"version":"2.0"},
         "extensionsRequired":["KHR_materials_ior","KHR_materials_transmission","KHR_materials_emissive_strength"],
         "buffers":[{"byteLength":84,"uri":"data:application/octet-stream;base64,%s"}],
         "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":48}],
         "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC4"}],
         "images":[{"name":"pixel","uri":"pixel.png"}],
         "samplers":[{"magFilter":9728,"minFilter":9984,"wrapS":33071,"wrapT":33648}],
         "textures":[{"source":0,"sampler":0}],
         "materials":[{"name":"glass","pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0.2,"roughnessFactor":0.3},"normalTexture":{"index":0,"texCoord":1,"scale":0.4},"emissiveFactor":[0.1,0.2,0.3],"alphaMode":"BLEND","doubleSided":true,"extensions":{"KHR_materials_ior":{"ior":1.33},"KHR_materials_transmission":{"transmissionFactor":0.6},"KHR_materials_emissive_strength":{"emissiveStrength":2}}}],
         "meshes":[{"name":"shared","primitives":[{"attributes":{"POSITION":0,"TANGENT":1},"material":0}]}],
         "nodes":[{"name":"root","children":[1,2]},{"name":"left","mesh":0,"translation":[2,0,0]},{"name":"right","mesh":0,"translation":[-2,0,0]}],
         "scenes":[{"nodes":[0]}],"scene":0}
        """
            .formatted(data);

    GltfLoader.Asset asset =
        GltfLoader.load(
            json.getBytes(),
            uri -> {
              assertEquals("pixel.png", uri);
              return new byte[] {1, 2, 3};
            });
    assertEquals(1, asset.meshes().size());
    assertEquals(0, asset.nodes().get(1).mesh());
    assertEquals(0, asset.nodes().get(2).mesh());
    assertEquals(2, asset.nodes().get(1).localMatrix()[12]);
    assertEquals(-2, asset.nodes().get(2).localMatrix()[12]);
    assertArrayEquals(
        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
        asset.meshes().getFirst().primitives().getFirst().positions(),
        0);
    assertEquals(12, asset.meshes().getFirst().primitives().getFirst().tangents().length);
    GltfLoader.Material material = asset.materials().getFirst();
    assertEquals(1.33f, material.ior());
    assertEquals(.6f, material.transmissionFactor());
    assertEquals(2, material.emissiveStrength());
    assertEquals(new GltfLoader.TextureInfo(0, 1, .4f), material.normalTexture());
    assertEquals(
        new GltfLoader.Sampler(
            GltfLoader.MagFilter.NEAREST,
            GltfLoader.MinFilter.NEAREST_MIPMAP_NEAREST,
            GltfLoader.Wrap.CLAMP_TO_EDGE,
            GltfLoader.Wrap.MIRRORED_REPEAT),
        asset.textures().getFirst().sampler());
    assertArrayEquals(new byte[] {1, 2, 3}, asset.images().getFirst().encoded());
  }

  @Test
  void rejectsNonTrianglePrimitiveClearly() {
    String json =
        """
        {"asset":{"version":"2.0"},"buffers":[],"bufferViews":[],"accessors":[],
         "meshes":[{"primitives":[{"mode":5,"attributes":{"POSITION":0}}]}],
         "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
        """;
    IOException failure =
        assertThrows(
            IOException.class, () -> GltfLoader.load(json.getBytes(), ignored -> new byte[0]));
    assertTrue(failure.getMessage().contains("TRIANGLES"));
  }

  @Test
  void rejectsMultipleParentNodeGraphs() {
    String json =
        """
        {"asset":{"version":"2.0"},
         "nodes":[{"children":[2]},{"children":[2]},{}],
         "scenes":[{"nodes":[0,1]}],"scene":0}
        """;
    IOException failure =
        assertThrows(
            IOException.class, () -> GltfLoader.load(json.getBytes(), ignored -> new byte[0]));
    assertTrue(failure.getMessage().contains("multiple parents"));
  }

  @Test
  void rejectsNonFiniteMaterialFactors() {
    String json =
        """
        {"asset":{"version":"2.0"},
         "materials":[{"pbrMetallicRoughness":{"metallicFactor":1e1000}}],
         "nodes":[],"scenes":[{"nodes":[]}],"scene":0}
        """;
    IOException failure =
        assertThrows(
            IOException.class, () -> GltfLoader.load(json.getBytes(), ignored -> new byte[0]));
    assertTrue(failure.getMessage().contains("finite"));
  }

  private static float[] identity() {
    return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }
}
