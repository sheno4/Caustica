package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureResource;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;

import java.util.List;
import java.util.function.Predicate;

/** Immutable Minecraft-owned emission semantics for one submitted material resource epoch. */
public interface MinecraftMaterialEmissionSnapshot {
    static MinecraftMaterialEmissionSnapshot empty() {
        return MinecraftMaterialEmissionCatalog.empty();
    }

    static MinecraftMaterialEmissionSnapshot build(List<MaterialDefinition> definitions,
                                                    List<MaterialRule> rules,
                                                    List<MaterialTextureResource> resources) {
        return MinecraftMaterialEmissionCatalog.build(definitions, rules, resources);
    }

    /** Resolve the first applicable Minecraft rule and reject a selected non-built-in surface that failed compilation. */
    Emission resolve(ResourceId material, ResourceId geometry, boolean emitting,
                     Predicate<ResourceId> surfaceAvailable);

    record Emission(float luminanceCdM2, boolean usesPrimitiveEmission, Footprint footprint) {
        public static final Emission NONE = new Emission(0.0f, false, null);

        public Emission {
            if (!Float.isFinite(luminanceCdM2) || luminanceCdM2 < 0.0f) {
                throw new IllegalArgumentException("emission luminance must be finite and non-negative");
            }
        }

        public boolean emissive() {
            return luminanceCdM2 > 0.0f;
        }
    }

    /** Worker-retainable pairing of Minecraft semantics with one engine compilation epoch. */
    record Published(MinecraftMaterialEmissionSnapshot semantics, MaterialSnapshot compilation) {
        public Published {
            java.util.Objects.requireNonNull(semantics, "semantics");
            java.util.Objects.requireNonNull(compilation, "compilation");
        }

        public long epoch() {
            return compilation.epoch();
        }

        public Emission resolve(SceneMesh.MaterialReference material) {
            return switch (material) {
                case SceneMesh.NamedMaterial named -> semantics.resolve(
                        named.material().id(), null, true, compilation::surfaceAvailable);
                case SceneMesh.CatalogMaterial catalog -> semantics.resolve(
                        catalog.material(), catalog.geometry(), catalog.variant().emitting(),
                        compilation::surfaceAvailable);
                default -> Emission.NONE;
            };
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
