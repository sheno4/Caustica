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

    static void write(ByteBuffer bytes, MinecraftEntityMesh source,
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
            bytes.putFloat(offset + TINT_OFFSET, 1);
            bytes.putFloat(offset + TINT_OFFSET + 4, 1);
            bytes.putFloat(offset + TINT_OFFSET + 8, 1);
            bytes.putInt(offset + MATERIAL_INDEX_OFFSET, materialIndices.get(triangle.material()));
            bytes.putInt(offset + BASE_TEXTURE_OFFSET, binding == null ? 0 : binding.image());
            bytes.putInt(offset + BASE_SAMPLER_OFFSET, binding == null ? 0 : binding.sampler());
            bytes.putInt(offset + TEXTURE_FLAGS_OFFSET, binding == null ? 0 : TEXTURE_PRESENT);
            bytes.putFloat(offset + PRIMITIVE_EMISSION_OFFSET, triangle.emission());
        }
        bytes.position(byteSize(source.triangleCount()));
    }

    static MinecraftPrimitiveData primitiveRecord(MinecraftEntityMesh.Triangle triangle,
                                                  MinecraftPrimitiveData.Float2[] uv,
                                                  int colorsOffset,
                                                  int materialIndex, Integer descriptor, Integer samplerDescriptor) {
        return new MinecraftPrimitiveData(uv, colorsOffset,
                new MinecraftPrimitiveData.Float3(1, 1, 1), materialIndex,
                new MinecraftPrimitiveData.SampledTexture2DIndex(descriptor == null ? 0 : descriptor),
                new MinecraftPrimitiveData.SamplerIndex(samplerDescriptor == null ? 0 : samplerDescriptor),
                descriptor == null ? 0 : TEXTURE_PRESENT, triangle.emission());
    }

    record TextureBinding(int image, int sampler) { }
}
