package dev.comfyfluffy.caustica.minecraft.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialClassifier;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class MinecraftMaterialSource implements MaterialSource {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_materials");
    public static final ResourceId CLOUD = ResourceId.of("caustica", "cloud");
    public static final ResourceId WATER = ResourceId.of("minecraft", "water");
    public static final int FORMAT = 4;

    @Override
    public void submitMaterials(MaterialSink sink) {
        sink.define(new MaterialDefinition(new MaterialHandle(CLOUD), 0.82f, 0.86f, 0.9f,
                0.92f, 0.0f, 1.33f, 0.0f, null));
        sink.define(waterDefinition());
        Map<Identifier, Resource> resources = Minecraft.getInstance().getResourceManager().listResources(
                "materials", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(resources.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        List<MaterialRule> rules = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : ordered) {
            try (Reader reader = entry.getValue().openAsReader()) {
                rules.add(parse(JsonParser.parseReader(reader).getAsJsonObject(), entry.getKey()));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("Ignoring invalid RT material override {}", entry.getKey(), throwable);
            }
        }
        // Geometry-specific rules win over texture-wide rules. Resource identifiers break ties after the
        // resource manager has selected the highest-priority pack for each identifier.
        rules.sort(Comparator.comparing((MaterialRule rule) -> rule.match().geometry() == null)
                .thenComparing(MaterialRule::id));
        rules.forEach(sink::submit);
        CausticaMod.LOGGER.info("RT material source: format={}, rules={}", FORMAT, rules.size());
    }

    static MaterialDefinition waterDefinition() {
        return new MaterialDefinition(new MaterialHandle(WATER), 1.0f, 1.0f, 1.0f,
                OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS, 0.0f,
                MinecraftMaterialClassifier.WATER_IOR, 1.0f,
                MinecraftProvidersExtension.WATER_SURFACE);
    }

    public static MaterialRule parse(JsonObject root, Identifier source) {
        int format = requiredInt(root, "format");
        if (format != FORMAT) throw new IllegalArgumentException("Unsupported material format " + format);
        JsonObject match = requiredObject(root, "match");
        ResourceId texture = ResourceId.parse(requiredString(match, "sprite"));
        ResourceId geometry = match.has("block")
                ? ResourceId.parse(match.get("block").getAsString()) : null;

        String model = null;
        if (root.has("model")) {
            model = switch (root.get("model").getAsString()) {
                case "opaque", "dielectric", "water" -> root.get("model").getAsString();
                default -> throw new IllegalArgumentException("Unknown material model");
            };
        }
        Float metalness = root.has("base") ? optionalFloat(root.getAsJsonObject("base"), "metalness") : null;
        Float roughness = null;
        Float ior = null;
        if (root.has("specular")) {
            JsonObject specular = root.getAsJsonObject("specular");
            roughness = optionalFloat(specular, "roughness");
            ior = optionalFloat(specular, "ior");
        }
        Float emissionLuminanceCdM2 = null;
        if (root.has("emission")) {
            JsonObject emission = root.getAsJsonObject("emission");
            emissionLuminanceCdM2 = optionalFloat(emission, "luminance_cd_m2");
            if (emission.has("color_source") && !"base_color".equals(emission.get("color_source").getAsString())) {
                throw new IllegalArgumentException("emission color_source must be base_color");
            }
        }
        if (emissionLuminanceCdM2 != null && Float.isFinite(emissionLuminanceCdM2)
                && (emissionLuminanceCdM2 < 0.0f || emissionLuminanceCdM2 > 65504.0f)) {
            float clamped = Math.max(0.0f, Math.min(65504.0f, emissionLuminanceCdM2));
            CausticaMod.LOGGER.warn("RT material override {}: emission.luminance_cd_m2 {} out of range "
                            + "[0,65504], clamping to {}", source, emissionLuminanceCdM2, clamped);
            emissionLuminanceCdM2 = clamped;
        }
        Float transmission = root.has("transmission")
                ? optionalFloat(root.getAsJsonObject("transmission"), "weight") : null;
        if (model != null) {
            if (transmission == null) transmission = "opaque".equals(model) ? 0.0f : 1.0f;
            if (ior == null) {
                ior = "water".equals(model) ? MinecraftMaterialClassifier.WATER_IOR : 1.5f;
            }
        }
        ResourceId surface = root.has("surface") ? ResourceId.parse(root.get("surface").getAsString())
                : "water".equals(model) ? MinecraftProvidersExtension.WATER_SURFACE : null;
        MaterialRule.Parameters parameters = new MaterialRule.Parameters(roughness, metalness, ior,
                transmission, emissionLuminanceCdM2, surface);
        return new MaterialRule(resourceId(source), new MaterialRule.Match(texture, geometry), parameters);
    }

    @Override
    public void shutdown() {
        RtBlockMaterials.INSTANCE.destroy();
    }

    private static ResourceId resourceId(Identifier id) {
        return ResourceId.of(id.getNamespace(), id.getPath());
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Missing object " + name);
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing string " + name);
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing integer " + name);
        return element.getAsInt();
    }

    private static Float optionalFloat(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsFloat() : null;
    }
}
