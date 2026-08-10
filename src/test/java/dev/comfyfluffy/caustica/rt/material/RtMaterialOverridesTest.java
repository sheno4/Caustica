package dev.comfyfluffy.caustica.rt.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialOverridesTest {
    @Test
    void parsesVersionedExtensibleMaterialProperties() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"block":"minecraft:blue_stained_glass",
                "sprite":"minecraft:block/blue_stained_glass"},"model":"dielectric",
                "base":{"metalness":0.0},"specular":{"roughness":0.06,"ior":1.52},
                "emission":{"luminance_cd_m2":2000.0,"color_source":"base_color"},
                "transmission":{"weight":1.0}}
                """).getAsJsonObject(), Identifier.parse("test:materials/glass.json"));
        assertEquals(Identifier.parse("minecraft:block/blue_stained_glass"), rule.sprite());
        assertEquals(RtMaterialRegistry.MODEL_DIELECTRIC, rule.model());
        assertEquals(1.52f, rule.ior());
        assertEquals(2000.0f, rule.emissionLuminanceCdM2());
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.LAB_PBR, RtMaterialRegistry.FEATURE_SPEC,
                0.8f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                5.0f, new RtMaterialDesc.EmissionSummary(0.2f, 0.1f, 0.05f, 0.1f, 0.5f));
        RtMaterialDesc applied = rule.apply(base);
        assertEquals(RtMaterialDesc.Source.OVERRIDE, applied.source());
        // luminance_cd_m2 replaces the level but keeps LabPBR's mask/source/summary.
        assertEquals(RtMaterialDesc.EmissionSource.LAB_PBR, applied.emissionSource());
        assertEquals(2000.0f, applied.emissionLuminance());
        assertEquals(base.emissionSummary(), applied.emissionSummary());
        assertEquals(0.06f, applied.specularRoughness());
        assertEquals(1.0f, applied.transmissionWeight());
    }

    @Test
    void specularIorOverridesTheBuiltInIndex() {
        RtMaterialDesc glassBase = new RtMaterialDesc(RtMaterialRegistry.MODEL_DIELECTRIC,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.05f, 0.0f, RtDielectrics.GLASS_IOR, 1.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE);
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"somemod:block/crystal"},
                "model":"dielectric","specular":{"ior":2.417}}
                """).getAsJsonObject(), Identifier.parse("test:materials/crystal.json"));
        RtMaterialDesc applied = rule.apply(glassBase);
        assertEquals(RtMaterialRegistry.MODEL_DIELECTRIC, applied.model());
        assertEquals(2.417f, applied.specularIor());

        // Omitting ior on a rule that does not change the model leaves the base index alone.
        var silent = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"somemod:block/crystal"},"specular":{"roughness":0.5}}
                """).getAsJsonObject(), Identifier.parse("test:materials/crystal.json"));
        assertEquals(RtDielectrics.GLASS_IOR, silent.apply(glassBase).specularIor());
    }

    @Test
    void waterModelKeepsItsOwnIndexWhenSelectedByName() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"somemod:block/pool"},"model":"water"}
                """).getAsJsonObject(), Identifier.parse("test:materials/pool.json"));
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE);
        RtMaterialDesc applied = rule.apply(base);
        assertEquals(RtMaterialRegistry.MODEL_WATER, applied.model());
        assertEquals(RtDielectrics.WATER_IOR, applied.specularIor());
        assertEquals(1.0f, applied.transmissionWeight());
    }

    @Test
    void emissionLuminanceCannotForceEmissionOntoANonEmissiveMaterial() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"minecraft:block/stone"},
                "emission":{"luminance_cd_m2":5000.0}}
                """).getAsJsonObject(), Identifier.parse("test:boost.json"));
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE);

        RtMaterialDesc applied = rule.apply(base);

        assertEquals(RtMaterialDesc.EmissionSource.NONE, applied.emissionSource());
        assertEquals(0.0f, applied.emissionLuminance());
    }

    @Test
    void rejectsUnknownVersionsAndOutOfRangePhysicalValues() {
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":1,\"match\":{\"sprite\":\"minecraft:block/stone\"}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":3,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"base\":{\"metalness\":2}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
    }

    @Test
    void clampsOutOfRangeEmissionLuminanceInsteadOfThrowing() {
        var rule = RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":3,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"emission\":{\"luminance_cd_m2\":70000}}")
                        .getAsJsonObject(), Identifier.parse("test:clamp.json"));
        assertEquals(65504.0f, rule.emissionLuminanceCdM2());
    }

    /**
     * A format-2 file authored roughness as GGX alpha under a different key. Accepting it would render
     * every such surface at the wrong gloss with no error, so the version gate is what makes that loud.
     */
    @Test
    void rejectsThePreviousFormatRatherThanReinterpretingItsRoughness() {
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":2,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"base\":{\"roughness\":0.25}}")
                        .getAsJsonObject(), Identifier.parse("test:legacy.json")));
    }

    @Test
    void spriteWideRulesApplyToCompiledEntityResources() {
        var entityRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"minecraft:entity/zombie/zombie"},
                "specular":{"roughness":0.7}}
                """).getAsJsonObject(), Identifier.parse("test:entity.json"));
        var blockRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"minecraft:entity/zombie/zombie",
                "block":"minecraft:stone"}}
                """).getAsJsonObject(), Identifier.parse("test:block.json"));

        assertTrue(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
        assertFalse(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/husk")));
        assertFalse(blockRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
    }
}
