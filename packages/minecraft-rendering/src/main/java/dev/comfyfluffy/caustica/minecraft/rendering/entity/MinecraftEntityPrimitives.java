package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;

/** Packs captured triangle shading data into the Minecraft primitive shader layout. */
final class MinecraftEntityPrimitives {
    private static final int TEXTURE_PRESENT = 1;

    private MinecraftEntityPrimitives() { }

    static void write(ByteBuffer bytes, MinecraftEntityMesh source, FloatBuffer positions,
                      IntBuffer indices, FloatBuffer uvs, FloatBuffer colors,
                      Map<MinecraftEntityMesh.Texture, TextureBinding> textures,
                      Map<MinecraftEntityMesh.Material, Integer> materialIndices) {
        for (int t = 0; t < source.triangleCount(); t++) {
            var triangle = source.triangles().get(t);
            MinecraftPrimitiveData.Float2[] uv = new MinecraftPrimitiveData.Float2[3];
            MinecraftPrimitiveData.Float4[] vertexColors = new MinecraftPrimitiveData.Float4[3];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = indices.get(t * 3 + corner);
                uv[corner] = new MinecraftPrimitiveData.Float2(
                        uvs.get(vertex * 2), uvs.get(vertex * 2 + 1));
                vertexColors[corner] = new MinecraftPrimitiveData.Float4(colors.get(vertex * 4),
                        colors.get(vertex * 4 + 1), colors.get(vertex * 4 + 2), colors.get(vertex * 4 + 3));
            }
            TextureBinding binding = triangle.material().texture() == null
                    ? null : textures.get(triangle.material().texture());
            TangentBasis basis = tangentBasis(positions, indices, uvs, t);
            var record = primitiveRecord(triangle, uv, vertexColors,
                    materialIndices.get(triangle.material()),
                    binding == null ? null : binding.image(), binding == null ? null : binding.sampler(), basis);
            record.write(bytes.slice(t * MinecraftPrimitiveData.BYTE_SIZE, MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
        }
        bytes.position(source.triangleCount() * MinecraftPrimitiveData.BYTE_SIZE);
    }

    static MinecraftPrimitiveData primitiveRecord(MinecraftEntityMesh.Triangle triangle,
                                                  MinecraftPrimitiveData.Float2[] uv,
                                                  MinecraftPrimitiveData.Float4[] colors,
                                                  int materialIndex, Integer descriptor, Integer samplerDescriptor,
                                                  TangentBasis basis) {
        if (colors.length != 3) throw new IllegalArgumentException("triangle needs three vertex colors");
        return new MinecraftPrimitiveData(uv, colors,
                new MinecraftPrimitiveData.Float3(1, 1, 1), materialIndex,
                new MinecraftPrimitiveData.SampledTexture2DIndex(descriptor == null ? 0 : descriptor),
                new MinecraftPrimitiveData.SamplerIndex(samplerDescriptor == null ? 0 : samplerDescriptor),
                descriptor == null ? 0 : TEXTURE_PRESENT, triangle.emission(),
                basis.tangent(), basis.bitangent());
    }

    static TangentBasis tangentBasis(FloatBuffer positions, IntBuffer indices, FloatBuffer uvs, int triangle) {
        int i0 = indices.get(triangle * 3), i1 = indices.get(triangle * 3 + 1), i2 = indices.get(triangle * 3 + 2);
        float x1 = positions.get(i1 * 3) - positions.get(i0 * 3);
        float y1 = positions.get(i1 * 3 + 1) - positions.get(i0 * 3 + 1);
        float z1 = positions.get(i1 * 3 + 2) - positions.get(i0 * 3 + 2);
        float x2 = positions.get(i2 * 3) - positions.get(i0 * 3);
        float y2 = positions.get(i2 * 3 + 1) - positions.get(i0 * 3 + 1);
        float z2 = positions.get(i2 * 3 + 2) - positions.get(i0 * 3 + 2);
        float u1 = uvs.get(i1 * 2) - uvs.get(i0 * 2), v1 = uvs.get(i1 * 2 + 1) - uvs.get(i0 * 2 + 1);
        float u2 = uvs.get(i2 * 2) - uvs.get(i0 * 2), v2 = uvs.get(i2 * 2 + 1) - uvs.get(i0 * 2 + 1);
        float determinant = u1 * v2 - u2 * v1;
        if (Math.abs(determinant) <= 1.0e-8f) return TangentBasis.ZERO;
        float inverse = 1.0f / determinant;
        return new TangentBasis(normalized((x1 * v2 - x2 * v1) * inverse,
                (y1 * v2 - y2 * v1) * inverse, (z1 * v2 - z2 * v1) * inverse),
                normalized((x2 * u1 - x1 * u2) * inverse,
                        (y2 * u1 - y1 * u2) * inverse, (z2 * u1 - z1 * u2) * inverse));
    }

    private static MinecraftPrimitiveData.Float3 normalized(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length <= 1.0e-8f) return new MinecraftPrimitiveData.Float3(0, 0, 0);
        return new MinecraftPrimitiveData.Float3(x / length, y / length, z / length);
    }

    record TextureBinding(int image, int sampler) { }
    record TangentBasis(MinecraftPrimitiveData.Float3 tangent, MinecraftPrimitiveData.Float3 bitangent) {
        static final TangentBasis ZERO = new TangentBasis(new MinecraftPrimitiveData.Float3(0, 0, 0),
                new MinecraftPrimitiveData.Float3(0, 0, 0));
    }
}
