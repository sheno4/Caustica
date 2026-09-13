package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftEntityPrimitivePackingTest {
    @Test void indexedPackingMatchesReflectedWriterIncludingPaddingAndMissingTextures() {
        var texture = MinecraftEntityMesh.Texture.standalone(ResourceId.of("test", "texture"));
        var textured = new MinecraftEntityMesh.Material(ResourceId.of("test", "textured"), texture,
                MinecraftEntityMesh.Program.MATERIAL);
        var plain = new MinecraftEntityMesh.Material(ResourceId.of("test", "plain"), null,
                MinecraftEntityMesh.Program.PORTAL);
        var triangles = List.of(
                new MinecraftEntityMesh.Triangle(textured, MinecraftEntityMesh.Coverage.CUTOUT, 2.5f),
                new MinecraftEntityMesh.Triangle(plain, MinecraftEntityMesh.Coverage.OPAQUE, 0),
                new MinecraftEntityMesh.Triangle(textured, MinecraftEntityMesh.Coverage.STOCHASTIC, 7));
        var mesh = new MinecraftEntityMesh(
                new float[]{0, 0, 0, 2, 0, 0, 0, 3, 1, -2, 3, 4},
                new int[]{2, 0, 1, 3, 1, 2, 0, 0, 0},
                new float[]{0, 0, 1, 0, 0, 1, -0.5f, 2},
                new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f,
                        0.9f, 1, 0, 0.25f, 2, 3, 4, 0.75f}, triangles, 1);
        var materials = Map.of(textured, 17, plain, 29);
        var positions = mesh.positions();
        var indices = mesh.indices();
        var uvs = mesh.uvs();
        var colors = mesh.vertexColors();
        positions.position(positions.limit());
        indices.position(indices.limit());
        uvs.position(uvs.limit());
        colors.position(colors.limit());

        for (boolean hasBinding : List.of(false, true)) {
            Map<MinecraftEntityMesh.Texture, MinecraftEntityPrimitives.TextureBinding> bindings = hasBinding
                    ? Map.of(texture, new MinecraftEntityPrimitives.TextureBinding(1234, 5678)) : Map.of();
            int size = triangles.size() * MinecraftPrimitiveData.BYTE_SIZE;
            byte[] actualStorage = new byte[size + 16], expectedStorage = new byte[size + 16];
            Arrays.fill(actualStorage, (byte) 0x5a);
            Arrays.fill(expectedStorage, (byte) 0x5a);
            var actual = ByteBuffer.wrap(actualStorage);
            var expected = ByteBuffer.wrap(expectedStorage);
            MinecraftEntityPrimitives.write(actual, mesh, positions, indices, uvs, colors, bindings, materials);

            for (int t = 0; t < triangles.size(); t++) {
                var triangle = triangles.get(t);
                var coordinates = new MinecraftPrimitiveData.Float2[3];
                var vertexColors = new MinecraftPrimitiveData.Float4[3];
                for (int corner = 0; corner < 3; corner++) {
                    int vertex = indices.get(t * 3 + corner);
                    coordinates[corner] = new MinecraftPrimitiveData.Float2(uvs.get(vertex * 2), uvs.get(vertex * 2 + 1));
                    vertexColors[corner] = new MinecraftPrimitiveData.Float4(colors.get(vertex * 4),
                            colors.get(vertex * 4 + 1), colors.get(vertex * 4 + 2), colors.get(vertex * 4 + 3));
                }
                var binding = triangle.material().texture() == null ? null : bindings.get(triangle.material().texture());
                var basis = MinecraftEntityPrimitives.tangentBasis(positions, indices, uvs, t);
                new MinecraftPrimitiveData(coordinates, vertexColors, new MinecraftPrimitiveData.Float3(1, 1, 1),
                        materials.get(triangle.material()),
                        new MinecraftPrimitiveData.SampledTexture2DIndex(binding == null ? 0 : binding.image()),
                        new MinecraftPrimitiveData.SamplerIndex(binding == null ? 0 : binding.sampler()),
                        binding == null ? 0 : 1, triangle.emission(), basis.tangent(), basis.bitangent())
                        .write(expected.slice(t * MinecraftPrimitiveData.BYTE_SIZE, MinecraftPrimitiveData.BYTE_SIZE)
                                .order(ByteOrder.LITTLE_ENDIAN));
            }
            assertArrayEquals(expectedStorage, actualStorage);
            assertEquals(size, actual.position());
            assertEquals(positions.limit(), positions.position());
            assertEquals(indices.limit(), indices.position());
            assertEquals(uvs.limit(), uvs.position());
            assertEquals(colors.limit(), colors.position());
        }
    }
}
