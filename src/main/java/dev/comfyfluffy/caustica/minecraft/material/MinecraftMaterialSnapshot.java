package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialResolution;

/** Immutable Minecraft-owned material semantics for one submitted resource epoch. */
public interface MinecraftMaterialSnapshot {
    static MinecraftMaterialSnapshot empty() { return MinecraftResolvedMaterialCatalog.empty(); }

    MinecraftMaterialResolution resolve(MinecraftMaterialKey key);
    MinecraftMaterialResolution named(MaterialHandle handle);

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
        public MinecraftMaterialEmission resolve(SceneMesh.MaterialReference material) {
            if (!(material instanceof SceneMesh.NamedMaterial named)) return MinecraftMaterialEmission.NONE;
            MinecraftMaterialResolution resolved = semantics.named(named.material());
            return available(resolved) ? resolved.emission() : MinecraftMaterialEmission.NONE;
        }
        public SceneMesh.OpacityMicromapRange opacityMicromapRange(SceneMesh.MaterialReference material) {
            if (!(material instanceof SceneMesh.NamedMaterial named)) return null;
            MinecraftMaterialResolution resolved = semantics.named(named.material());
            return available(resolved) ? resolved.opacityMicromapRange() : null;
        }
        private ResolvedMaterial published(MinecraftMaterialResolution resolved,
                                           SceneMesh.TextureReference texture) {
            boolean available = available(resolved);
            return new ResolvedMaterial(new SceneMesh.NamedMaterial(resolved.definition().handle(), texture),
                    available ? resolved.emission() : MinecraftMaterialEmission.NONE,
                    available ? resolved.opacityMicromapRange() : null);
        }
        private boolean available(MinecraftMaterialResolution resolved) {
            if (resolved == null) return false;
            return compilation.surfaceAvailable(resolved.definition().surface());
        }
    }

    record ResolvedMaterial(SceneMesh.NamedMaterial material, MinecraftMaterialEmission emission,
                            SceneMesh.OpacityMicromapRange opacityMicromapRange) {
        public ResolvedMaterial {
            java.util.Objects.requireNonNull(material, "material");
            java.util.Objects.requireNonNull(emission, "emission");
        }
    }

}
