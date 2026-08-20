package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered provider material rules compiled ahead of source textures and renderer defaults. Parameters
 * use OpenPBR meanings; the optional surface names a registered {@code ISurfaceModel} implementation.
 */
public final class RtMaterialOverrides {
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

    public static RtMaterialOverrides from(List<MaterialRule> submitted, SurfaceResolver surfaces) {
        List<Rule> rules = new ArrayList<>();
        for (MaterialRule rule : submitted) {
            try {
                rules.add(compile(rule, surfaces));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("Ignoring invalid RT material rule {}", rule.id(), throwable);
            }
        }
        CausticaMod.LOGGER.info("RT material rules: submitted={}, compiled={}", submitted.size(), rules.size());
        return rules.isEmpty() ? EMPTY : new RtMaterialOverrides(rules);
    }

    private static Rule compile(MaterialRule rule, SurfaceResolver surfaces) {
        MaterialRule.Parameters parameters = rule.parameters();
        Integer transport = parameters.topology() == null ? null
                : RtMaterialRegistry.transport(parameters.topology());
        Integer surfaceImplementation = null;
        if (parameters.surface() != null) {
            int index = surfaces.indexOf(parameters.surface());
            if (index < 0) {
                CausticaMod.LOGGER.warn("Material rule {} names unregistered surface {}; using the error surface",
                        rule.id(), parameters.surface());
                index = RtMaterialRegistry.ERROR_SURFACE_IMPLEMENTATION;
            }
            surfaceImplementation = index;
        }
        return new Rule(rule.id(), rule.match().material(), rule.match().geometry(),
                transport, parameters.specularRoughness(),
                parameters.baseMetalness(), parameters.specularIor(), parameters.transmissionWeight(),
                parameters.emissionLuminanceCdM2(), surfaceImplementation);
    }

    public List<Rule> rules() {
        return rules;
    }

    public record Rule(ResourceId source, ResourceId material, ResourceId geometry, Integer transport,
                       Float roughness, Float metalness, Float ior, Float transmission,
                       /**
                        * OpenPBR {@code emission_luminance}: absolute emitting-surface luminance in cd/m²
                        * for whatever emission mask the material naturally resolves to (authored,
                        * derived, or geometry-uniform). A material with no
                        * natural emission stays unlit.
                        */
                       Float emissionLuminanceCdM2,
                       /**
                        * Registered surface-implementation index, already resolved from the authored
                        * {@code surface} name. Null leaves the material on what it inherited, which for
                        * everything compiled today is the built-in surface. An authored unresolved name
                        * is non-null and carries the visible error implementation instead.
                        */
                       Integer surfaceImplementation) {
        boolean matchesMaterial(ResourceId value) {
            return material.equals(value);
        }

        boolean matches(ResourceId material, ResourceId geometry) {
            return matchesMaterial(material) && (this.geometry == null || this.geometry.equals(geometry));
        }

        RtMaterialDesc apply(RtMaterialDesc base, boolean emissionCapable) {
            int nextTransport = transport != null ? transport : base.transport();
            float nextRoughness = roughness != null ? roughness : base.specularRoughness();
            float nextMetalness = metalness != null ? metalness : base.baseMetalness();
            float nextIor = ior != null ? ior
                    : (transport != null ? defaultIor(nextTransport) : base.specularIor());
            float nextTransmission = transmission != null ? transmission
                    : (transport != null ? defaultTransmission(nextTransport) : base.transmissionWeight());
            // An absolute emitting-surface luminance. Existing texture/state emission keeps its mask;
            // an override does not create one.
            float nextEmissionLuminance = emissionLuminanceCdM2 != null
                    && emissionCapable
                    ? emissionLuminanceCdM2 : base.emissionLuminance();
            return new RtMaterialDesc(nextTransport, RtMaterialDesc.Source.OVERRIDE, base.features(),
                    nextRoughness, nextMetalness, nextIor, nextTransmission,
                    base.transmissionColorR(), base.transmissionColorG(), base.transmissionColorB(),
                    base.subsurfaceWeight(), base.subsurfaceColorR(), base.subsurfaceColorG(),
                    base.subsurfaceColorB(), base.subsurfaceScatterAnisotropy(),
                    base.emissionColorR(), base.emissionColorG(), base.emissionColorB(),
                    nextEmissionLuminance,
                    surfaceImplementation != null ? surfaceImplementation : base.surfaceImplementation());
        }

        private static float defaultIor(int transport) {
            return transport == RtMaterialRegistry.TRANSPORT_MEDIUM_BOUNDARY
                    ? OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_IOR
                    : OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
        }

        private static float defaultTransmission(int transport) {
            return transport == RtMaterialRegistry.TRANSPORT_MEDIUM_BOUNDARY
                    ? 1.0f : 0.0f;
        }
    }

}
