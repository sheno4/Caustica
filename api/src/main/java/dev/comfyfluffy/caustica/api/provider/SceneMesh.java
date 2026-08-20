package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable indexed scene mesh with optional vertex attributes and source-level triangle shading facts.
 * Positions and placement translations use scene world units. RGB values crossing this boundary are
 * scene-linear ACEScg (AP1/D60); alpha, UVs, normals, and emission strength are unitless.
 */
public final class SceneMesh {
    public enum UvLayout { PER_VERTEX, PER_TRIANGLE_CORNER }
    public enum Coverage { OPAQUE, CUTOUT, STOCHASTIC }
    /**
     * Conservative coverage bounds over one triangle for every state the provider may render.
     * Absence means the engine must classify the triangle as unknown. An incorrect bound can create
     * visible holes because an opacity micromap classified as opaque skips any-hit entirely.
     */
    public record OpacityMicromapRange(float minCoverage, float maxCoverage) {
        public OpacityMicromapRange {
            finite(minCoverage, maxCoverage);
            if (minCoverage < 0.0f || maxCoverage > 1.0f || minCoverage > maxCoverage) {
                throw new IllegalArgumentException("opacity micromap range must be ordered within [0,1]");
            }
        }
    }
    /** Mesh-wide renderer-neutral geometry semantics. */
    public enum Semantic { RECEIVES_PROJECTED_SURFACE_MODIFIERS }

    /** Source-level material identity resolved by the renderer for its current material epoch. */
    public sealed interface MaterialReference permits NamedMaterial, FallbackMaterial {
        TextureReference texture();
    }
    /** Stable texture identity local to the scene provider that submitted this mesh. */
    public sealed interface TextureReference permits AtlasTexture, StandaloneTexture { }
    public record AtlasTexture(ResourceId atlas) implements TextureReference {
        public AtlasTexture { Objects.requireNonNull(atlas, "atlas"); }
    }
    public record StandaloneTexture(ResourceId texture) implements TextureReference {
        public StandaloneTexture { Objects.requireNonNull(texture, "texture"); }
    }
    /** A provider-defined material. Its definition owns surface, topology, and material parameters. */
    public record NamedMaterial(MaterialHandle material, TextureReference texture)
            implements MaterialReference {
        public NamedMaterial { Objects.requireNonNull(material, "material"); }
        public NamedMaterial(MaterialHandle material) { this(material, null); }
    }
    /** The renderer's neutral surface, optionally paired with a source texture. */
    public record FallbackMaterial(TextureReference texture) implements MaterialReference { }

    /** One material and shading description for one indexed triangle; {@code tint*} is scene-linear ACEScg. */
    public record TriangleSurface(MaterialReference material,
                                  Coverage coverage, float normalX, float normalY, float normalZ,
                                  float emission, float tintR, float tintG, float tintB,
                                  OpacityMicromapRange opacityMicromapRange,
                                  boolean emitterInLightScene) {
        public TriangleSurface {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(coverage, "coverage");
            if ((!Float.isFinite(normalX) || !Float.isFinite(normalY) || !Float.isFinite(normalZ))
                    && !(Float.isNaN(normalX) && Float.isNaN(normalY) && Float.isNaN(normalZ))) {
                throw new IllegalArgumentException("triangle normal must be finite or omitted");
            }
            finite(emission, tintR, tintG, tintB);
        }

        public TriangleSurface(MaterialReference material, Coverage coverage, float normalX, float normalY, float normalZ,
                               float emission, float tintR, float tintG, float tintB) {
            this(material, coverage, normalX, normalY, normalZ, emission, tintR, tintG, tintB, null, false);
        }

        /** Returns this surface marked as represented by a retained emitter light. */
        public TriangleSurface withEmitterInLightScene() {
            return emitterInLightScene ? this : new TriangleSurface(material, coverage, normalX, normalY, normalZ,
                    emission, tintR, tintG, tintB, opacityMicromapRange, true);
        }

        public TriangleSurface withOpacityMicromapRange(OpacityMicromapRange range) {
            return new TriangleSurface(material, coverage, normalX, normalY, normalZ,
                    emission, tintR, tintG, tintB, Objects.requireNonNull(range, "range"), emitterInLightScene);
        }

        public static TriangleSurface surface(MaterialHandle material) {
            return new TriangleSurface(new NamedMaterial(material),
                    Coverage.OPAQUE, Float.NaN, Float.NaN, Float.NaN, 0f, 1f, 1f, 1f, null, false);
        }
    }

    private final float[] positions;
    private final int[] indices;
    private final UvLayout uvLayout;
    private final float[] textureCoordinates;
    private final float[] vertexNormals;
    private final float[] vertexColors;
    private final List<TriangleSurface> surfaces;
    private final Set<Semantic> semantics;

    public SceneMesh(float[] positions, int[] indices, UvLayout uvLayout, float[] textureCoordinates,
                     List<TriangleSurface> surfaces) {
        this(positions, indices, uvLayout, textureCoordinates, surfaces, Set.of());
    }

    public SceneMesh(float[] positions, int[] indices, UvLayout uvLayout, float[] textureCoordinates,
                     List<TriangleSurface> surfaces, Set<Semantic> semantics) {
        this(positions, indices, uvLayout, textureCoordinates, new float[0], new float[0], surfaces, semantics);
    }

    /**
     * Creates a mesh with optional indexed shading attributes. Vertex colors are scene-linear ACEScg RGB
     * with unitless alpha multipliers. An empty normal or color array selects the corresponding
     * per-triangle surface fallback; the fallback color uses the triangle tint with alpha one.
     */
    public SceneMesh(float[] positions, int[] indices, UvLayout uvLayout, float[] textureCoordinates,
                     float[] vertexNormals, float[] vertexColors,
                     List<TriangleSurface> surfaces, Set<Semantic> semantics) {
        this.positions = positions.clone();
        this.indices = indices.clone();
        this.uvLayout = Objects.requireNonNull(uvLayout, "uvLayout");
        this.textureCoordinates = textureCoordinates.clone();
        this.vertexNormals = vertexNormals.clone();
        this.vertexColors = vertexColors.clone();
        this.surfaces = List.copyOf(surfaces);
        this.semantics = Set.copyOf(semantics);
        validate();
    }

    public float[] positions() { return positions.clone(); }
    public int[] indices() { return indices.clone(); }
    public UvLayout uvLayout() { return uvLayout; }
    public float[] textureCoordinates() { return textureCoordinates.clone(); }
    public float[] vertexNormals() { return vertexNormals.clone(); }
    public float[] vertexColors() { return vertexColors.clone(); }
    public List<TriangleSurface> surfaces() { return surfaces; }
    public Set<Semantic> semantics() { return semantics; }
    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }

    private void validate() {
        if (positions.length == 0 || positions.length % 3 != 0 || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("scene mesh must contain complete vertices and triangles");
        }
        finite(positions);
        int expectedUvs = uvLayout == UvLayout.PER_VERTEX ? vertexCount() * 2 : indices.length * 2;
        if (textureCoordinates.length != expectedUvs) {
            throw new IllegalArgumentException("scene mesh texture coordinates do not match their layout");
        }
        finite(textureCoordinates);
        if (vertexNormals.length != 0 && vertexNormals.length != vertexCount() * 3) {
            throw new IllegalArgumentException("scene mesh vertex normals do not match its vertices");
        }
        finite(vertexNormals);
        if (vertexColors.length != 0 && vertexColors.length != vertexCount() * 4) {
            throw new IllegalArgumentException("scene mesh vertex colors do not match its vertices");
        }
        finite(vertexColors);
        if (surfaces.size() != triangleCount()) {
            throw new IllegalArgumentException("scene mesh needs one surface per triangle");
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount()) throw new IllegalArgumentException("scene mesh index outside vertices");
        }
    }

    private static void finite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("scene mesh value is not finite");
    }

}
