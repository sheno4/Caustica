package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureResource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Builds and resolves the Minecraft-owned immutable emission catalog for one resource epoch. */
final class MinecraftMaterialEmissionCatalog implements MinecraftMaterialEmissionSnapshot {
    private record Base(float luminanceCdM2, boolean usesPrimitiveEmission,
                        MinecraftEmissionFootprint footprint, ResourceId surface) {
        MinecraftMaterialEmissionSnapshot.Emission emission(float luminance) {
            return luminance > 0.0f
                    ? new MinecraftMaterialEmissionSnapshot.Emission(luminance, usesPrimitiveEmission,
                    footprint)
                    : MinecraftMaterialEmissionSnapshot.Emission.NONE;
        }
    }

    private final Map<ResourceId, Base> materials;
    private final List<MaterialRule> rules;

    private MinecraftMaterialEmissionCatalog(Map<ResourceId, Base> materials, List<MaterialRule> rules) {
        this.materials = Map.copyOf(materials);
        this.rules = List.copyOf(rules);
    }

    static MinecraftMaterialEmissionCatalog empty() {
        return new MinecraftMaterialEmissionCatalog(Map.of(), List.of());
    }

    static MinecraftMaterialEmissionCatalog build(List<MaterialDefinition> definitions,
                                                   List<MaterialRule> rules,
                                                   List<MaterialTextureResource> resources) {
        Map<ResourceId, MaterialTextureResource> resourcesById = new LinkedHashMap<>();
        for (MaterialTextureResource resource : resources) {
            resourcesById.put(resource.material(), resource);
        }
        for (MaterialDefinition definition : definitions) {
            if (definition.textureResource() != null) {
                resourcesById.put(definition.id(), definition.textureResource());
            }
        }
        Map<ResourceId, MinecraftMaterialEmissionAnalyzer.Scan> scans = new HashMap<>();
        for (MaterialTextureResource resource : resourcesById.values()) {
            try {
                scans.put(resource.material(), MinecraftMaterialEmissionAnalyzer.scan(resource));
            } catch (Throwable failure) {
                CausticaMod.LOGGER.warn("Minecraft emission scan failed for {}", resource.material(), failure);
            }
        }

        Map<ResourceId, Base> materials = new HashMap<>();
        for (MaterialTextureResource resource : resourcesById.values()) {
            MinecraftMaterialEmissionAnalyzer.Scan scan = scans.get(resource.material());
            MinecraftEmissionFootprint footprint = scan == null ? null
                    : resource.emissionMask() ? scan.masked() : scan.uniform();
            materials.put(resource.material(), new Base(resource.uniformEmissionLuminanceCdM2(), true,
                    footprint, null));
        }
        for (MaterialDefinition definition : definitions) {
            MaterialTextureResource resource = resourcesById.get(definition.id());
            MinecraftMaterialEmissionAnalyzer.Scan scan = scans.get(definition.id());
            boolean textureHasEmissionMask = resource != null && resource.emissionMask();
            MinecraftEmissionFootprint footprint;
            if (textureHasEmissionMask) {
                footprint = scan == null ? null : scan.masked();
            } else {
                footprint = MinecraftMaterialEmissionAnalyzer.constant(definition.emissionColorR(),
                        definition.emissionColorG(), definition.emissionColorB());
            }
            materials.put(definition.id(), new Base(definition.emissionLuminanceCdM2(),
                    false, footprint, definition.surface()));
        }
        return new MinecraftMaterialEmissionCatalog(materials, rules);
    }

    @Override
    public Emission resolve(ResourceId material, ResourceId geometry, boolean emitting,
                            Predicate<ResourceId> surfaceAvailable) {
        Base base = materials.get(material);
        if (base == null) return Emission.NONE;
        if (base.usesPrimitiveEmission() && !emitting) return Emission.NONE;
        MaterialRule rule = matchingRule(material, geometry);
        ResourceId surface = rule != null && rule.parameters().surface() != null
                ? rule.parameters().surface() : base.surface();
        if (surface != null && !surfaceAvailable.test(surface)) return Emission.NONE;
        float luminance = rule != null && rule.parameters().emissionLuminanceCdM2() != null
                ? rule.parameters().emissionLuminanceCdM2() : base.luminanceCdM2();
        return base.emission(luminance);
    }

    private MaterialRule matchingRule(ResourceId material, ResourceId geometry) {
        if (geometry != null) {
            for (MaterialRule rule : rules) {
                if (rule.match().material().equals(material) && geometry.equals(rule.match().geometry())) {
                    return rule;
                }
            }
        }
        for (MaterialRule rule : rules) {
            if (rule.match().material().equals(material) && rule.match().geometry() == null) {
                return rule;
            }
        }
        return null;
    }
}
