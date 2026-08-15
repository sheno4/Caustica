package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable indexed scene mesh with source-level triangle shading facts. */
public final class SceneMesh {
    public enum UvLayout { PER_VERTEX, PER_TRIANGLE_CORNER }
    public enum Coverage { OPAQUE, CUTOUT, STOCHASTIC }
    /** Mesh-wide renderer-neutral geometry semantics. */
    public enum Semantic { RECEIVES_PROJECTED_SURFACE_MODIFIERS }

    /** Source-level material identity resolved by the renderer for its current material epoch. */
    public sealed interface MaterialReference permits NamedMaterial, CatalogMaterial, AtlasMaterial, StandaloneMaterial, FallbackMaterial {
        TextureReference texture();
    }
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
    /** A renderer catalog material selected by resource identity, geometry, and variant. */
    public record CatalogMaterial(ResourceId material, ResourceId geometry, MaterialVariant variant, TextureReference texture)
            implements MaterialReference {
        public CatalogMaterial {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(variant, "variant");
        }
        public CatalogMaterial(ResourceId material, ResourceId geometry, MaterialVariant variant) {
            this(material, geometry, variant, null);
        }
    }
    public record AtlasMaterial(AtlasMaterialReference reference) implements MaterialReference {
        public AtlasMaterial { Objects.requireNonNull(reference, "reference"); }
        @Override public TextureReference texture() { return new AtlasTexture(reference.atlas()); }
    }
    public record StandaloneMaterial(ResourceId material) implements MaterialReference {
        public StandaloneMaterial { Objects.requireNonNull(material, "material"); }
        @Override public TextureReference texture() { return new StandaloneTexture(material); }
    }
    /** The renderer's neutral surface, optionally paired with a source texture. */
    public record FallbackMaterial(TextureReference texture) implements MaterialReference { }

    /** One material and shading description for one indexed triangle. */
    public record TriangleSurface(MaterialReference material,
                                  Coverage coverage, float normalX, float normalY, float normalZ,
                                  float emission, float tintR, float tintG, float tintB,
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
            this(material, coverage, normalX, normalY, normalZ, emission, tintR, tintG, tintB, false);
        }

        /** Returns this surface marked as represented by a retained emitter light. */
        public TriangleSurface withEmitterInLightScene() {
            return emitterInLightScene ? this : new TriangleSurface(material, coverage, normalX, normalY, normalZ,
                    emission, tintR, tintG, tintB, true);
        }

        public static TriangleSurface surface(MaterialHandle material) {
            return new TriangleSurface(new NamedMaterial(material),
                    Coverage.OPAQUE, Float.NaN, Float.NaN, Float.NaN, 0f, 1f, 1f, 1f, false);
        }
    }

    private final float[] positions;
    private final int[] indices;
    private final UvLayout uvLayout;
    private final float[] textureCoordinates;
    private final List<TriangleSurface> surfaces;
    private final Set<Semantic> semantics;

    public SceneMesh(float[] positions, int[] indices, UvLayout uvLayout, float[] textureCoordinates,
                     List<TriangleSurface> surfaces) {
        this(positions, indices, uvLayout, textureCoordinates, surfaces, Set.of());
    }

    public SceneMesh(float[] positions, int[] indices, UvLayout uvLayout, float[] textureCoordinates,
                     List<TriangleSurface> surfaces, Set<Semantic> semantics) {
        this.positions = positions.clone();
        this.indices = indices.clone();
        this.uvLayout = Objects.requireNonNull(uvLayout, "uvLayout");
        this.textureCoordinates = textureCoordinates.clone();
        this.surfaces = List.copyOf(surfaces);
        this.semantics = Set.copyOf(semantics);
        validate();
    }

    public float[] positions() { return positions.clone(); }
    public int[] indices() { return indices.clone(); }
    public UvLayout uvLayout() { return uvLayout; }
    public float[] textureCoordinates() { return textureCoordinates.clone(); }
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
