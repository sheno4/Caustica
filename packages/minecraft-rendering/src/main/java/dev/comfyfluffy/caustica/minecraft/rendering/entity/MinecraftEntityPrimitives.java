package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;

import static dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData.*;

/** Packs captured triangle shading data into the Minecraft primitive shader layout. */
final class MinecraftEntityPrimitives {
    private static final int TEXTURE_PRESENT = 1;
    static final int COLOR_BYTES = 3 * 4 * Float.BYTES;

    static int byteSize(int triangles) { return Math.multiplyExact(triangles, BYTE_SIZE + COLOR_BYTES); }

    private MinecraftEntityPrimitives() { }

    static void write(ByteBuffer bytes, MinecraftEntityMesh source, FloatBuffer positions,
                      IntBuffer indices, FloatBuffer uvs, FloatBuffer colors,
                      Map<MinecraftEntityMesh.Texture, TextureBinding> textures,
                      Map<MinecraftEntityMesh.Material, Integer> materialIndices) {
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        for (int t = 0; t < source.triangleCount(); t++) {
            var triangle = source.triangles().get(t);
            int offset = t * BYTE_SIZE;
            int colorOffset = source.triangleCount() * BYTE_SIZE + t * COLOR_BYTES;
            clearPadding(bytes, offset);
            bytes.putInt(offset + VERTEX_COLORS_OFFSET_OFFSET, colorOffset - offset);
            for (int corner = 0; corner < 3; corner++) {
                int vertex = indices.get(t * 3 + corner);
                int uv = offset + TEXTURE_COORDINATES_OFFSET + corner * TEXTURE_COORDINATES_STRIDE;
                bytes.putFloat(uv, uvs.get(vertex * 2));
                bytes.putFloat(uv + 4, uvs.get(vertex * 2 + 1));
                int color = colorOffset + corner * 4 * Float.BYTES;
                for (int lane = 0; lane < 4; lane++) {
                    bytes.putFloat(color + lane * 4, colors.get(vertex * 4 + lane));
                }
            }
            TextureBinding binding = triangle.material().texture() == null
                    ? null : textures.get(triangle.material().texture());
            TangentBasis basis = tangentBasis(positions, indices, uvs, t);
            bytes.putFloat(offset + TINT_OFFSET, 1);
            bytes.putFloat(offset + TINT_OFFSET + 4, 1);
            bytes.putFloat(offset + TINT_OFFSET + 8, 1);
            bytes.putInt(offset + MATERIAL_INDEX_OFFSET, materialIndices.get(triangle.material()));
            bytes.putInt(offset + BASE_TEXTURE_OFFSET, binding == null ? 0 : binding.image());
            bytes.putInt(offset + BASE_SAMPLER_OFFSET, binding == null ? 0 : binding.sampler());
            bytes.putInt(offset + TEXTURE_FLAGS_OFFSET, binding == null ? 0 : TEXTURE_PRESENT);
            bytes.putFloat(offset + PRIMITIVE_EMISSION_OFFSET, triangle.emission());
            bytes.putFloat(offset + TANGENT_OFFSET, basis.tangent().x());
            bytes.putFloat(offset + TANGENT_OFFSET + 4, basis.tangent().y());
            bytes.putFloat(offset + TANGENT_OFFSET + 8, basis.tangent().z());
            bytes.putFloat(offset + BITANGENT_OFFSET, basis.bitangent().x());
            bytes.putFloat(offset + BITANGENT_OFFSET + 4, basis.bitangent().y());
            bytes.putFloat(offset + BITANGENT_OFFSET + 8, basis.bitangent().z());
        }
        bytes.position(byteSize(source.triangleCount()));
    }

    static MinecraftPrimitiveData primitiveRecord(MinecraftEntityMesh.Triangle triangle,
                                                  MinecraftPrimitiveData.Float2[] uv,
                                                  int colorsOffset,
                                                  int materialIndex, Integer descriptor, Integer samplerDescriptor,
                                                  TangentBasis basis) {
        return new MinecraftPrimitiveData(uv, colorsOffset,
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
