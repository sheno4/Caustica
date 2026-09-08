package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.MinecraftTextureSampler;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Immutable CPU geometry captured from one Minecraft entity, block entity, or particle group. */
public final class MinecraftEntityMesh {
    private final float[] positions;
    private final int[] indices;
    private final float[] uvs;
    private final float[] vertexColors;
    private final List<Triangle> triangles;
    private final long indexRevision;

    public MinecraftEntityMesh(float[] positions, int[] indices, float[] uvs, float[] vertexColors,
                               List<Triangle> triangles, long indexRevision) {
        this(positions, indices, uvs, vertexColors, triangles, indexRevision,
                positions.length, indices.length, uvs.length, vertexColors.length);
    }

    /** Copies the populated prefixes of reusable capture buffers into an immutable mesh. */
    public static MinecraftEntityMesh copyOfRanges(float[] positions, int[] indices, float[] uvs,
                                                   float[] vertexColors, int vertexCount, int indexCount,
                                                   List<Triangle> triangles, long indexRevision) {
        return new MinecraftEntityMesh(positions, indices, uvs, vertexColors, triangles, indexRevision,
                vertexCount * 3, indexCount, vertexCount * 2, vertexCount * 4);
    }

    private MinecraftEntityMesh(float[] positions, int[] indices, float[] uvs, float[] vertexColors,
                                List<Triangle> triangles, long indexRevision,
                                int positionCount, int indexCount, int uvCount, int colorCount) {
        positions = Arrays.copyOf(positions, positionCount);
        indices = Arrays.copyOf(indices, indexCount);
        uvs = Arrays.copyOf(uvs, uvCount);
        vertexColors = Arrays.copyOf(vertexColors, colorCount);
        triangles = List.copyOf(triangles);
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain float3 vertices");
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices must contain complete triangles");
        }
        if (uvs.length != positions.length / 3 * 2) {
            throw new IllegalArgumentException("UVs must contain one float2 per vertex");
        }
        if (vertexColors.length != positions.length / 3 * 4) {
            throw new IllegalArgumentException("vertex colors must contain one float4 per vertex");
        }
        if (triangles.size() != indices.length / 3) {
            throw new IllegalArgumentException("every triangle needs captured shading data");
        }
        int vertexCount = positions.length / 3;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("index exceeds the vertex stream");
            }
        }
        this.positions = positions;
        this.indices = indices;
        this.uvs = uvs;
        this.vertexColors = vertexColors;
        this.triangles = triangles;
        this.indexRevision = indexRevision;
    }

    // Each reader owns its cursor; the captured streams stay immutable and need no second copy.
    public FloatBuffer positions() { return FloatBuffer.wrap(positions).asReadOnlyBuffer(); }
    public IntBuffer indices() { return IntBuffer.wrap(indices).asReadOnlyBuffer(); }
    public FloatBuffer uvs() { return FloatBuffer.wrap(uvs).asReadOnlyBuffer(); }
    public FloatBuffer vertexColors() { return FloatBuffer.wrap(vertexColors).asReadOnlyBuffer(); }
    public List<Triangle> triangles() { return triangles; }
    public long indexRevision() { return indexRevision; }

    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }

    /** Shader family selected independently from the captured material and texture identity. */
    public enum Program { MATERIAL, PORTAL }

    /** Captured traversal behavior; stochastic alpha remains distinct for the uploader's primitive ABI. */
    public enum Coverage { OPAQUE, CUTOUT, STOCHASTIC }

    public enum TextureKind { STANDALONE, ATLAS }

    /** Stable Minecraft texture identity. The uploader resolves its current Vulkan view at upload time. */
    public record Texture(ResourceId resource, TextureKind kind, MinecraftTextureSampler sampler) {
        public Texture {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sampler, "sampler");
        }

        public static Texture standalone(ResourceId resource) {
            return standalone(resource, MinecraftTextureSampler.PIXEL_ART);
        }

        public static Texture standalone(ResourceId resource, MinecraftTextureSampler sampler) {
            return new Texture(resource, TextureKind.STANDALONE, sampler);
        }

        public static Texture atlas(ResourceId resource) {
            return atlas(resource, MinecraftTextureSampler.PIXEL_ART);
        }

        public static Texture atlas(ResourceId resource, MinecraftTextureSampler sampler) {
            return new Texture(resource, TextureKind.ATLAS, sampler);
        }
    }

    /** Minecraft material identity plus its optional sampled base-color texture. */
    public record Material(ResourceId material, Texture texture, Program program,
                           MinecraftMaterialProfile profile, boolean mediumBoundary) {
        public Material {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(profile, "profile");
        }

        public Material(ResourceId material, Texture texture, Program program) {
            this(material, texture, program, MinecraftMaterialProfile.ROUGH_DIELECTRIC, false);
        }
    }

    /** Per-triangle source data consumed by Minecraft's primitive-record uploader. */
    public record Triangle(Material material, Coverage coverage,
                           float emission) {
        public Triangle {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(coverage, "coverage");
        }
    }
}
