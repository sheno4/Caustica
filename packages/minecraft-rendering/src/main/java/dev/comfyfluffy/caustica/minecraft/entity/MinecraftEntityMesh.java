package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.List;
import java.util.Objects;

/** Immutable CPU geometry captured from one Minecraft entity, block entity, or particle group. */
public record MinecraftEntityMesh(float[] positions, int[] indices, float[] uvs,
                                  List<Triangle> triangles, long indexRevision) {
    public MinecraftEntityMesh {
        positions = positions.clone();
        indices = indices.clone();
        uvs = uvs.clone();
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
        if (triangles.size() != indices.length / 3) {
            throw new IllegalArgumentException("every triangle needs captured shading data");
        }
        int vertexCount = positions.length / 3;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("index exceeds the vertex stream");
            }
        }
    }

    @Override public float[] positions() { return positions.clone(); }
    @Override public int[] indices() { return indices.clone(); }
    @Override public float[] uvs() { return uvs.clone(); }

    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }

    /** Shader family selected independently from the captured material and texture identity. */
    public enum Program { MATERIAL, PORTAL }

    public enum MaterialProfile { ROUGH_DIELECTRIC, SMOOTH_DIELECTRIC }

    /** Captured traversal behavior; stochastic alpha remains distinct for the uploader's primitive ABI. */
    public enum Coverage { OPAQUE, CUTOUT, STOCHASTIC }

    public enum TextureKind { STANDALONE, ATLAS }

    /** Stable Minecraft texture identity. The uploader resolves its current Vulkan view at upload time. */
    public record Texture(ResourceId resource, TextureKind kind) {
        public Texture {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(kind, "kind");
        }

        public static Texture standalone(ResourceId resource) {
            return new Texture(resource, TextureKind.STANDALONE);
        }

        public static Texture atlas(ResourceId resource) {
            return new Texture(resource, TextureKind.ATLAS);
        }
    }

    /** Minecraft material identity plus its optional sampled base-color texture. */
    public record Material(ResourceId material, Texture texture, Program program,
                           MaterialProfile profile, boolean mediumBoundary) {
        public Material {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(profile, "profile");
        }

        public Material(ResourceId material, Texture texture, Program program) {
            this(material, texture, program, MaterialProfile.ROUGH_DIELECTRIC, false);
        }
    }

    /** Per-triangle source data consumed by Minecraft's primitive-record uploader. */
    public record Triangle(Material material, Coverage coverage,
                           float normalX, float normalY, float normalZ,
                           float emission, float tintR, float tintG, float tintB) {
        public Triangle {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(coverage, "coverage");
        }
    }
}
