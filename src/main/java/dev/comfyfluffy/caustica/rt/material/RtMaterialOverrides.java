package dev.comfyfluffy.caustica.rt.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Optional resource-pack material properties compiled ahead of LabPBR and engine heuristics. Keys are
 * OpenPBR parameter names, so {@code specular.roughness} is perceptual and {@code specular.ior} drives
 * both the Fresnel split and the Snell bend. The one non-OpenPBR key is {@code surface}, naming the
 * registered {@code ISurfaceModel} implementation the description is routed through.
 */
public final class RtMaterialOverrides {
    public static final int FORMAT = 4;
    public static final RtMaterialOverrides EMPTY = new RtMaterialOverrides(List.of());

    private final List<Rule> rules;

    private RtMaterialOverrides(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Resolves an authored {@code surface} name to a registered implementation index. Names are resolved
     * once, here, so the compiled material carries only the index and the shader never sees an identifier.
     */
    public interface SurfaceResolver {
        int indexOf(ResourceId surfaceId);
    }

    public static RtMaterialOverrides load(SurfaceResolver surfaces) {
        Map<Identifier, Resource> resources = Minecraft.getInstance().getResourceManager().listResources(
                "materials", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(resources.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : ordered) {
            try (Reader reader = entry.getValue().openAsReader()) {
                rules.add(parse(JsonParser.parseReader(reader).getAsJsonObject(), entry.getKey(), surfaces));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("Ignoring invalid RT material override {}", entry.getKey(), throwable);
            }
        }
        // More-specific block+sprite rules win over sprite-wide rules. Ties use the resource identifier,
        // while the resource manager has already selected the highest-priority pack for each identifier.
        rules.sort(Comparator.comparing((Rule rule) -> rule.block() == null)
                .thenComparing(rule -> rule.source().toString()));
        CausticaMod.LOGGER.info("RT material overrides: format={}, rules={}", FORMAT, rules.size());
        return rules.isEmpty() ? EMPTY : new RtMaterialOverrides(rules);
    }

    static Rule parse(JsonObject root, Identifier source, SurfaceResolver surfaces) {
        int format = requiredInt(root, "format");
        if (format != FORMAT) throw new IllegalArgumentException("Unsupported material format " + format);
        JsonObject match = requiredObject(root, "match");
        Identifier sprite = Identifier.parse(requiredString(match, "sprite"));
        Identifier block = match.has("block") ? Identifier.parse(match.get("block").getAsString()) : null;

        Integer model = null;
        if (root.has("model")) {
            // "water" is the animated fluid surface (waves, caustics, biome-tint absorption); "dielectric" is
            // every other transparent material.
            model = switch (root.get("model").getAsString()) {
                case "opaque" -> RtMaterialRegistry.MODEL_OPAQUE;
                case "water" -> RtMaterialRegistry.MODEL_WATER;
                case "dielectric" -> RtMaterialRegistry.MODEL_DIELECTRIC;
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
        Float transmission = root.has("transmission")
                ? optionalFloat(root.getAsJsonObject("transmission"), "weight") : null;
        Integer surfaceImplementation = null;
        if (root.has("surface")) {
            ResourceId surfaceId = ResourceId.parse(root.get("surface").getAsString());
            int index = surfaces.indexOf(surfaceId);
            if (index < 0) {
                throw new IllegalArgumentException("No registered surface implementation " + surfaceId);
            }
            surfaceImplementation = index;
        }
        validate01("specular.roughness", roughness);
        validate01("base.metalness", metalness);
        validate01("transmission.weight", transmission);
        if (ior != null && (!Float.isFinite(ior) || ior <= 0.0f)) {
            throw new IllegalArgumentException("specular.ior must be positive");
        }
        if (emissionLuminanceCdM2 != null && !Float.isFinite(emissionLuminanceCdM2)) {
            throw new IllegalArgumentException("emission.luminance_cd_m2 must be finite");
        }
        if (emissionLuminanceCdM2 != null
                && (emissionLuminanceCdM2 < 0.0f || emissionLuminanceCdM2 > 65504.0f)) {
            float clamped = Math.max(0.0f, Math.min(65504.0f, emissionLuminanceCdM2));
            CausticaMod.LOGGER.warn("RT material override {}: emission.luminance_cd_m2 {} out of range "
                            + "[0,65504], clamping to {}",
                    source, emissionLuminanceCdM2, clamped);
            emissionLuminanceCdM2 = clamped;
        }
        return new Rule(source, sprite, block, model, roughness, metalness, ior, transmission,
                emissionLuminanceCdM2, surfaceImplementation);
    }

    public List<Rule> rules() {
        return rules;
    }

    public record Rule(Identifier source, Identifier sprite, Identifier block, Integer model,
                       Float roughness, Float metalness, Float ior, Float transmission,
                       /**
                        * OpenPBR {@code emission_luminance}: absolute emitting-surface luminance in cd/m²
                        * for whatever emission mask the material naturally resolves to (LabPBR
                        * {@code _s}, heuristic mask, or state-uniform block light). A material with no
                        * natural emission stays unlit.
                        */
                       Float emissionLuminanceCdM2,
                       /**
                        * Registered surface-implementation index, already resolved from the authored
                        * {@code surface} name. Null leaves the material on what it inherited, which for
                        * everything compiled today is the built-in surface.
                        */
                       Integer surfaceImplementation) {
        boolean matchesSprite(TextureAtlasSprite value) {
            return value != null && sprite.equals(value.contents().name());
        }

        boolean matchesEntity(Identifier value) {
            return block == null && sprite.equals(value);
        }

        boolean matches(TextureAtlasSprite value, BlockState state) {
            if (!matchesSprite(value)) return false;
            return block == null || state != null && block.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }

        RtMaterialDesc apply(RtMaterialDesc base) {
            int nextModel = model != null ? model : base.model();
            float nextRoughness = roughness != null ? roughness : base.specularRoughness();
            float nextMetalness = metalness != null ? metalness : base.baseMetalness();
            float nextIor = ior != null ? ior
                    : (model != null ? defaultIor(nextModel) : base.specularIor());
            float nextTransmission = transmission != null ? transmission
                    : (model != null ? defaultTransmission(nextModel) : base.transmissionWeight());
            // An absolute emitting-surface luminance. It can replace the level of an existing
            // LabPBR/heuristic/state emitter but does not create an emission mask where none exists.
            float nextEmissionLuminance = emissionLuminanceCdM2 != null
                    && base.emissionSource() != RtMaterialDesc.EmissionSource.NONE
                    ? emissionLuminanceCdM2 : base.emissionLuminance();
            return new RtMaterialDesc(nextModel, RtMaterialDesc.Source.OVERRIDE, base.features(),
                    nextRoughness, nextMetalness, nextIor, nextTransmission,
                    base.emissionSource(), nextEmissionLuminance, base.emissionSummary(),
                    surfaceImplementation != null ? surfaceImplementation : base.surfaceImplementation());
        }

        private static float defaultIor(int model) {
            return model == RtMaterialRegistry.MODEL_WATER ? RtDielectrics.WATER_IOR
                    : model == RtMaterialRegistry.MODEL_DIELECTRIC ? RtDielectrics.GLASS_IOR
                    : RtDielectrics.DEFAULT_IOR;
        }

        private static float defaultTransmission(int model) {
            return model == RtMaterialRegistry.MODEL_WATER || model == RtMaterialRegistry.MODEL_DIELECTRIC
                    ? 1.0f : 0.0f;
        }
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

    private static void validate01(String name, Float value) {
        if (value != null && (!Float.isFinite(value) || value < 0.0f || value > 1.0f)) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }
}
