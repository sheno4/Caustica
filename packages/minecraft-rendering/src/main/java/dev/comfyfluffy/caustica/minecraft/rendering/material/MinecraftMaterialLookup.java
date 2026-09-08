package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureResource;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftEmissionFootprint;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmissionAnalyzer;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialRule;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTexture;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrDefaults;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable Minecraft material-table lookup for one resource-pack epoch. */
public final class MinecraftMaterialLookup {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftMaterialLookup.class);
    private final ResourcePackEpoch epoch;
    private final Map<MinecraftMaterialKey, MinecraftMaterialResolution> resolutions;
    private final Map<ResourceId, MinecraftMaterialResolution> defaults;
    private final List<MinecraftMaterialRecord> records;
    private final List<MinecraftMaterialTexture> textures;

    private MinecraftMaterialLookup(ResourcePackEpoch epoch,
                                    Map<MinecraftMaterialKey, MinecraftMaterialResolution> resolutions,
                                    Map<ResourceId, MinecraftMaterialResolution> defaults,
                                    List<MinecraftMaterialRecord> records,
                                    List<MinecraftMaterialTexture> textures) {
        this.epoch = epoch;
        this.resolutions = Map.copyOf(resolutions);
        this.defaults = Map.copyOf(defaults);
        this.records = List.copyOf(records);
        this.textures = List.copyOf(textures);
    }

    public static MinecraftMaterialLookup compile(ResourcePackEpoch epoch, List<MinecraftMaterialRule> rules,
                                                   List<MaterialTextureResource> resources,
                                                   MinecraftMaterialPageCompiler.Result pages) {
        java.util.Objects.requireNonNull(epoch, "epoch");
        List<MinecraftMaterialRecord> records = new ArrayList<>();
        records.add(MinecraftMaterialRecord.fallback());
        Map<MinecraftMaterialKey, MinecraftMaterialResolution> resolutions = new LinkedHashMap<>();
        Map<ResourceId, MinecraftMaterialResolution> defaults = new LinkedHashMap<>();
        int waterIndex = records.size();
        records.add(MinecraftMaterialRecord.waterBoundary());
        defaults.put(MinecraftMaterialIds.WATER, new MinecraftMaterialResolution(waterIndex,
                MinecraftMaterialIds.WATER, MinecraftMaterialTopology.MEDIUM_BOUNDARY,
                MinecraftMaterialEmission.NONE));
        Map<ResourceId, MinecraftMaterialEmissionAnalyzer.Scan> scans = scan(resources);
        Map<ResourceId, Set<ResourceId>> geometries = geometryCases(rules);
        List<MaterialTextureResource> ordered = new ArrayList<>(resources);
        ordered.sort(Comparator.comparing(MaterialTextureResource::material));

        for (MaterialTextureResource resource : ordered) {
            List<ResourceId> geometryCases = new ArrayList<>();
            geometryCases.add(null);
            geometries.getOrDefault(resource.material(), Set.of()).stream()
                    .sorted(Comparator.comparing(ResourceId::toString)).forEach(geometryCases::add);
            for (ResourceId geometry : geometryCases) {
                MinecraftMaterialRule rule = matchingRule(rules, resource.material(), geometry);
                for (MinecraftMaterialProfile profile : MinecraftMaterialProfile.values()) {
                    for (MinecraftMaterialTopology topology : MinecraftMaterialTopology.values()) {
                        MinecraftMaterialKey key = new MinecraftMaterialKey(resource.material(), geometry,
                                profile, topology);
                        Compiled resolved = compileRecord(key, resource, pages.material(resource.material()),
                                rule, scans.get(resource.material()));
                        int index = records.size();
                        records.add(resolved.record());
                        MinecraftMaterialResolution resolution = new MinecraftMaterialResolution(index,
                                resource.material(), resolved.topology(), resolved.emission());
                        resolutions.put(key, resolution);
                        if (geometry == null && profile == MinecraftMaterialProfile.ROUGH_DIELECTRIC
                                && topology == MinecraftMaterialTopology.SURFACE) {
                            defaults.put(resource.material(), resolution);
                        }
                    }
                }
            }
        }
        for (ResourceId material : MinecraftMaterialIds.CAPTURE_SURFACES) {
            MinecraftMaterialResolution resolution = new MinecraftMaterialResolution(0, material,
                    MinecraftMaterialTopology.SURFACE, MinecraftMaterialEmission.NONE);
            resolutions.put(new MinecraftMaterialKey(material, null,
                    MinecraftMaterialProfile.ROUGH_DIELECTRIC, MinecraftMaterialTopology.SURFACE), resolution);
            defaults.put(material, resolution);
        }
        return new MinecraftMaterialLookup(epoch, resolutions, defaults, records, pages.textures());
    }

    public ResourcePackEpoch epoch() { return epoch; }
    public List<MinecraftMaterialRecord> records() { return records; }
    public List<MinecraftMaterialTexture> textures() { return textures; }

    public MinecraftMaterialResolution resolve(MinecraftMaterialKey key) {
        MinecraftMaterialResolution value = resolutions.get(key);
        if (value == null && key.geometry() != null) value = resolutions.get(key.defaultGeometry());
        if (value == null) throw new IllegalArgumentException("No Minecraft material for " + key);
        return value;
    }

    /** Resolve captured entity material metadata, or use the neutral record for an unindexed vanilla texture. */
    public MinecraftMaterialResolution resolveEntityOrFallback(MinecraftMaterialKey key) {
        MinecraftMaterialResolution value = resolutions.get(key);
        if (value == null && key.geometry() != null) value = resolutions.get(key.defaultGeometry());
        return value != null ? value : new MinecraftMaterialResolution(0, key.material(), key.topology(),
                MinecraftMaterialEmission.NONE);
    }

    public MinecraftMaterialResolution resolve(ResourceId material) {
        MinecraftMaterialResolution value = defaults.get(material);
        if (value == null) throw new IllegalArgumentException("No Minecraft material for " + material);
        return value;
    }

    private static Map<ResourceId, MinecraftMaterialEmissionAnalyzer.Scan> scan(
            List<MaterialTextureResource> resources) {
        Map<ResourceId, MinecraftMaterialEmissionAnalyzer.Scan> scans = new HashMap<>();
        for (MaterialTextureResource resource : resources) {
            try {
                scans.put(resource.material(), MinecraftMaterialEmissionAnalyzer.scan(resource));
            } catch (Throwable failure) {
                LOGGER.warn(
                        "Minecraft material semantics scan failed for {}", resource.material(), failure);
            }
        }
        return scans;
    }

    private static Map<ResourceId, Set<ResourceId>> geometryCases(List<MinecraftMaterialRule> rules) {
        Map<ResourceId, Set<ResourceId>> result = new HashMap<>();
        for (MinecraftMaterialRule rule : rules) {
            if (rule.geometry() != null) {
                result.computeIfAbsent(rule.material(), ignored -> new HashSet<>()).add(rule.geometry());
            }
        }
        return result;
    }

    private static Compiled compileRecord(MinecraftMaterialKey key, MaterialTextureResource resource,
                                          MinecraftMaterialPageCompiler.CompiledMaterial page,
                                          MinecraftMaterialRule rule,
                                          MinecraftMaterialEmissionAnalyzer.Scan scan) {
        int features = page.features();
        MinecraftMaterialRule.Parameters parameters = rule == null ? null : rule.parameters();
        MinecraftMaterialTopology topology = parameters != null && parameters.topology() != null
                ? parameters.topology() : key.topology();
        float roughness = topology == MinecraftMaterialTopology.MEDIUM_BOUNDARY
                ? OpenPbrDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS : roughness(key.profile());
        float metalness = topology == MinecraftMaterialTopology.MEDIUM_BOUNDARY
                ? 0.0f : key.profile() == MinecraftMaterialProfile.CONDUCTOR ? 1.0f : 0.0f;
        float subsurface = 0.0f;
        if ((features & MinecraftMaterialPageCompiler.FEATURE_SPEC) != 0) {
            // Authored texture channels carry the weights; the record supplies their multipliers.
            roughness = 1.0f;
            metalness = 1.0f;
            subsurface = 1.0f;
        }
        float ior = topology == MinecraftMaterialTopology.MEDIUM_BOUNDARY
                ? resource.dielectricIor() : OpenPbrDefaults.SPECULAR_IOR;
        float transmission = topology == MinecraftMaterialTopology.MEDIUM_BOUNDARY ? 1.0f : 0.0f;
        float luminance = resource.uniformEmissionLuminanceCdM2();
        if (parameters != null) {
            if (parameters.specularRoughness() != null) roughness = parameters.specularRoughness();
            if (parameters.baseMetalness() != null) metalness = parameters.baseMetalness();
            if (parameters.specularIor() != null) ior = parameters.specularIor();
            if (parameters.transmissionWeight() != null) transmission = parameters.transmissionWeight();
            if (parameters.emissionLuminanceCdM2() != null) luminance = parameters.emissionLuminanceCdM2();
        }
        MinecraftEmissionFootprint footprint = scan == null ? null
                : (features & MinecraftMaterialPageCompiler.FEATURE_EMISSION_MASK) != 0
                ? scan.masked() : scan.uniform();
        MinecraftMaterialEmission emission = luminance > 0.0f && footprint != null
                ? new MinecraftMaterialEmission(luminance, true, footprint) : MinecraftMaterialEmission.NONE;
        MinecraftMaterialRecord record = MinecraftMaterialRecord.from(page,
                MinecraftMaterialRecord.Color3.WHITE, metalness, roughness, ior, transmission, subsurface,
                MinecraftMaterialRecord.Color3.WHITE, luminance);
        return new Compiled(record, topology, emission);
    }

    private static float roughness(MinecraftMaterialProfile profile) {
        return switch (profile) {
            case ROUGH_DIELECTRIC -> 0.9f;
            case CONDUCTOR -> 0.3f;
            case SMOOTH_DIELECTRIC -> 0.1f;
            case POLISHED_DIELECTRIC -> 0.35f;
            case MEDIUM_ROUGH_DIELECTRIC -> 0.7f;
        };
    }

    private static MinecraftMaterialRule matchingRule(List<MinecraftMaterialRule> rules,
                                                       ResourceId material, ResourceId geometry) {
        if (geometry != null) {
            for (MinecraftMaterialRule rule : rules) {
                if (rule.geometry() != null && rule.matches(material, geometry)) return rule;
            }
        }
        for (MinecraftMaterialRule rule : rules) {
            if (rule.geometry() == null && rule.matches(material, geometry)) return rule;
        }
        return null;
    }

    private record Compiled(MinecraftMaterialRecord record, MinecraftMaterialTopology topology,
                            MinecraftMaterialEmission emission) { }
}
