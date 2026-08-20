package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Immutable definitions and geometry/light semantics resolved by Minecraft for one resource epoch. */
public final class MinecraftResolvedMaterialCatalog implements MinecraftMaterialSnapshot {
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    public static final int FEATURE_EMISSION_MASK = 4;
    public static final int FEATURE_SUBSURFACE_COLOR_BASE = 8;
    public static final int FEATURE_EMISSION_COLOR_BASE = 16;

    public record Resolved(MaterialDefinition definition, Emission emission,
                           SceneMesh.OpacityMicromapRange opacityMicromapRange) {
        public Resolved {
            java.util.Objects.requireNonNull(definition, "definition");
            java.util.Objects.requireNonNull(emission, "emission");
        }

        public MaterialHandle handle() {
            return definition.handle();
        }
    }

    private final Map<MinecraftMaterialKey, Resolved> keyed;
    private final Map<ResourceId, Resolved> named;
    private final List<MaterialDefinition> definitions;

    private MinecraftResolvedMaterialCatalog(Map<MinecraftMaterialKey, Resolved> keyed,
                                             Map<ResourceId, Resolved> named,
                                             List<MaterialDefinition> definitions) {
        this.keyed = Map.copyOf(keyed);
        this.named = Map.copyOf(named);
        this.definitions = List.copyOf(definitions);
    }

    public static MinecraftResolvedMaterialCatalog empty() {
        return new MinecraftResolvedMaterialCatalog(Map.of(), Map.of(), List.of());
    }

    public static MinecraftResolvedMaterialCatalog build(List<MaterialDefinition> fixedDefinitions,
                                                          List<MinecraftMaterialRule> rules,
                                                          List<MaterialTextureResource> resources,
                                                          MinecraftMaterialPageCompiler.Result pages,
                                                          ResourceId defaultSurface) {
        Objects.requireNonNull(defaultSurface, "defaultSurface");
        Map<ResourceId, MaterialTextureResource> resourcesById = new LinkedHashMap<>();
        resources.stream().sorted(Comparator.comparing(MaterialTextureResource::material))
                .forEach(resource -> resourcesById.put(resource.material(), resource));

        Map<ResourceId, MinecraftMaterialEmissionAnalyzer.Scan> scans = new HashMap<>();
        for (MaterialTextureResource resource : resourcesById.values()) {
            try {
                scans.put(resource.material(), MinecraftMaterialEmissionAnalyzer.scan(resource));
            } catch (Throwable failure) {
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn(
                        "Minecraft material semantics scan failed for {}", resource.material(), failure);
            }
        }

        Map<ResourceId, Set<ResourceId>> geometries = new HashMap<>();
        for (MinecraftMaterialRule rule : rules) {
            if (rule.geometry() != null && resourcesById.containsKey(rule.material())) {
                geometries.computeIfAbsent(rule.material(), ignored -> new HashSet<>()).add(rule.geometry());
            }
        }

        List<MinecraftMaterialKey> keys = new ArrayList<>();
        for (ResourceId material : resourcesById.keySet()) {
            List<ResourceId> cases = new ArrayList<>();
            cases.add(null);
            geometries.getOrDefault(material, Set.of()).stream()
                    .sorted(Comparator.comparing(ResourceId::toString)).forEach(cases::add);
            for (ResourceId geometry : cases) {
                for (MinecraftMaterialProfile profile : MinecraftMaterialProfile.values()) {
                    for (MaterialTopology topology : MaterialTopology.values()) {
                        keys.add(new MinecraftMaterialKey(material, geometry, profile, topology));
                    }
                }
            }
        }
        keys.sort(Comparator.comparing(MinecraftResolvedMaterialCatalog::canonicalKey));

        Map<MinecraftMaterialKey, Resolved> keyed = new LinkedHashMap<>();
        Map<ResourceId, Resolved> named = new LinkedHashMap<>();
        Map<ResourceId, MinecraftMaterialKey> handleKeys = new HashMap<>();
        List<MaterialDefinition> definitions = new ArrayList<>(fixedDefinitions.size() + keys.size());
        for (MaterialDefinition definition : fixedDefinitions) {
            Resolved resolved = fixed(definition);
            if (named.putIfAbsent(definition.id(), resolved) != null) {
                throw new IllegalStateException("duplicate Minecraft material " + definition.id());
            }
            definitions.add(definition);
        }
        for (MinecraftMaterialKey key : keys) {
            MaterialTextureResource resource = resourcesById.get(key.material());
            MinecraftMaterialPageCompiler.CompiledMaterial page = pages.material(key.material());
            MinecraftMaterialRule rule = matchingRule(rules, key.material(), key.geometry());
            MaterialHandle handle = handle(key, handleKeys);
            Resolved resolved = compile(handle, key, resource, page, rule, scans.get(key.material()),
                    defaultSurface);
            keyed.put(key, resolved);
            if (named.putIfAbsent(handle.id(), resolved) != null) {
                throw new IllegalStateException("duplicate resolved Minecraft material " + handle.id());
            }
            definitions.add(resolved.definition());
        }
        return new MinecraftResolvedMaterialCatalog(keyed, named, definitions);
    }

    public List<MaterialDefinition> definitions() {
        return definitions;
    }

    public Resolved resolve(MinecraftMaterialKey key) {
        Resolved resolved = keyed.get(key);
        if (resolved == null && key.geometry() != null) resolved = keyed.get(key.defaultGeometry());
        if (resolved == null) throw new IllegalArgumentException("No resolved Minecraft material for " + key);
        return resolved;
    }

    public Resolved named(MaterialHandle handle) {
        return named.get(handle.id());
    }

    private static Resolved fixed(MaterialDefinition definition) {
        MinecraftEmissionFootprint footprint = definition.emissionLuminanceCdM2() > 0.0f
                ? MinecraftMaterialEmissionAnalyzer.constant(definition.emissionColorR(),
                definition.emissionColorG(), definition.emissionColorB()) : null;
        Emission emission = footprint == null ? Emission.NONE
                : new Emission(definition.emissionLuminanceCdM2(), false, footprint);
        return new Resolved(definition, emission, null);
    }

    private static Resolved compile(MaterialHandle handle, MinecraftMaterialKey key,
                                    MaterialTextureResource resource,
                                    MinecraftMaterialPageCompiler.CompiledMaterial page,
                                    MinecraftMaterialRule rule,
                                    MinecraftMaterialEmissionAnalyzer.Scan scan,
                                    ResourceId defaultSurface) {
        int features = page.features();
        MaterialTopology topology = key.topology();
        float roughness = topology == MaterialTopology.MEDIUM_BOUNDARY
                ? OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS
                : key.profile().specularRoughness();
        float metalness = topology == MaterialTopology.MEDIUM_BOUNDARY
                ? 0.0f : key.profile().baseMetalness();
        if ((features & FEATURE_SPEC) != 0) {
            roughness = 1.0f;
            metalness = 1.0f;
        }
        float ior = topology == MaterialTopology.MEDIUM_BOUNDARY
                ? resource.dielectricIor() : OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
        float transmission = topology == MaterialTopology.MEDIUM_BOUNDARY ? 1.0f : 0.0f;
        float luminance = resource.uniformEmissionLuminanceCdM2();
        ResourceId surface = defaultSurface;

        if (rule != null) {
            MinecraftMaterialRule.Parameters parameters = rule.parameters();
            if (parameters.topology() != null && parameters.topology() != topology) {
                topology = parameters.topology();
                ior = topology == MaterialTopology.MEDIUM_BOUNDARY
                        ? OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_IOR
                        : OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
                transmission = topology == MaterialTopology.MEDIUM_BOUNDARY ? 1.0f : 0.0f;
            }
            if (parameters.specularRoughness() != null) roughness = parameters.specularRoughness();
            if (parameters.baseMetalness() != null) metalness = parameters.baseMetalness();
            if (parameters.specularIor() != null) ior = parameters.specularIor();
            if (parameters.transmissionWeight() != null) transmission = parameters.transmissionWeight();
            if (parameters.emissionLuminanceCdM2() != null) luminance = parameters.emissionLuminanceCdM2();
            if (parameters.surface() != null) surface = parameters.surface();
        }

        MaterialDefinition definition = new MaterialDefinition(handle,
                1, 1, 1, roughness, metalness, ior, transmission,
                1, 1, 1, 1, 0.8f, 0.8f, 0.8f, 0,
                1, 1, 1, luminance, topology, surface, 0.5f, page.providerData());
        MinecraftEmissionFootprint footprint = scan == null ? null
                : (features & FEATURE_EMISSION_MASK) != 0 ? scan.masked() : scan.uniform();
        Emission emission = luminance > 0.0f && footprint != null
                ? new Emission(luminance, true, footprint) : Emission.NONE;
        SceneMesh.OpacityMicromapRange opacityRange = surface.equals(defaultSurface) && scan != null
                ? scan.opacityRange() : null;
        return new Resolved(definition, emission, opacityRange);
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

    private static MaterialHandle handle(MinecraftMaterialKey key,
                                         Map<ResourceId, MinecraftMaterialKey> handleKeys) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalKey(key).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        StringBuilder path = new StringBuilder("resolved/");
        for (int i = 0; i < 16; i++) path.append(String.format("%02x", digest[i] & 255));
        ResourceId id = ResourceId.of("caustica", path.toString());
        MinecraftMaterialKey collision = handleKeys.putIfAbsent(id, key);
        if (collision != null && !collision.equals(key)) {
            throw new IllegalStateException("resolved Minecraft material handle collision between "
                    + collision + " and " + key);
        }
        return new MaterialHandle(id);
    }

    static String canonicalKey(MinecraftMaterialKey key) {
        return component(key.material().toString()) + component(key.geometry() == null ? "" : key.geometry().toString())
                + component(key.profile().name()) + component(key.topology().name());
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }
}
