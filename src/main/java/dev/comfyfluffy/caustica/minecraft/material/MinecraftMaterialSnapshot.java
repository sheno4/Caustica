package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;

/** Immutable Minecraft-owned material semantics for one submitted resource epoch. */
public interface MinecraftMaterialSnapshot {
    static MinecraftMaterialSnapshot empty() { return MinecraftResolvedMaterialCatalog.empty(); }

    MinecraftResolvedMaterialCatalog.Resolved resolve(MinecraftMaterialKey key);
    MinecraftResolvedMaterialCatalog.Resolved named(MaterialHandle handle);

    record Emission(float luminanceCdM2, boolean usesPrimitiveEmission, Footprint footprint) {
        public static final Emission NONE = new Emission(0.0f, false, null);
        public Emission {
            if (!Float.isFinite(luminanceCdM2) || luminanceCdM2 < 0.0f) {
                throw new IllegalArgumentException("emission luminance must be finite and non-negative");
            }
        }
        public boolean emissive() { return luminanceCdM2 > 0.0f; }
    }

    /** Worker-retainable pairing of Minecraft semantics with one engine compilation epoch. */
    record Published(MinecraftMaterialSnapshot semantics, MaterialSnapshot compilation) {
        public Published {
            java.util.Objects.requireNonNull(semantics, "semantics");
            java.util.Objects.requireNonNull(compilation, "compilation");
        }
        public long epoch() { return compilation.epoch(); }
        public ResolvedMaterial resolve(MinecraftMaterialKey key, SceneMesh.TextureReference texture) {
            return published(semantics.resolve(key), texture);
        }
        public Emission resolve(SceneMesh.MaterialReference material) {
            if (!(material instanceof SceneMesh.NamedMaterial named)) return Emission.NONE;
            MinecraftResolvedMaterialCatalog.Resolved resolved = semantics.named(named.material());
            return available(resolved) ? resolved.emission() : Emission.NONE;
        }
        public SceneMesh.OpacityMicromapRange opacityMicromapRange(SceneMesh.MaterialReference material) {
            if (!(material instanceof SceneMesh.NamedMaterial named)) return null;
            MinecraftResolvedMaterialCatalog.Resolved resolved = semantics.named(named.material());
            return available(resolved) ? resolved.opacityMicromapRange() : null;
        }
        private ResolvedMaterial published(MinecraftResolvedMaterialCatalog.Resolved resolved,
                                           SceneMesh.TextureReference texture) {
            boolean available = available(resolved);
            return new ResolvedMaterial(new SceneMesh.NamedMaterial(resolved.handle(), texture),
                    available ? resolved.emission() : Emission.NONE,
                    available ? resolved.opacityMicromapRange() : null);
        }
        private boolean available(MinecraftResolvedMaterialCatalog.Resolved resolved) {
            if (resolved == null) return false;
            var surface = resolved.definition().surface();
            return surface == null || compilation.surfaceAvailable(surface);
        }
    }

    record ResolvedMaterial(SceneMesh.NamedMaterial material, Emission emission,
                            SceneMesh.OpacityMicromapRange opacityMicromapRange) {
        public ResolvedMaterial {
            java.util.Objects.requireNonNull(material, "material");
            java.util.Objects.requireNonNull(emission, "emission");
        }
    }

    /** Fixed-resolution premultiplied linear-BT.709 emission color and coverage. */
    interface Footprint {
        int resolution();
        int sampleIndex(float coordinate);
        float r(int x, int y);
        float g(int x, int y);
        float b(int x, int y);
        float weight(int x, int y);
    }
}
