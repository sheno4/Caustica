package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyCloudPassTest {
    @TempDir Path directory;

    @Test void sourceTransformsAndParentPlacementSelectActualDensity() throws Exception {
        var fixture = fixture();
        shared(fixture.component()).put("RainPillarRadiusAsFraction[5]", 37);
        var model = CloudlyCloudPass.SourceModel.from(load(fixture));
        var component = model.components().getFirst();
        assertEquals(1, model.components().size());
        assertEquals(0, component.textureId());
        assertEquals(10, component.centerAndDensity().x());
        assertEquals(30, component.centerAndDensity().y());
        assertEquals(-20, component.centerAndDensity().z());
        assertEquals(0.27759f, component.centerAndDensity().w());
        assertEquals(0.5f, component.inverseSizeAndCos().x());
        assertEquals(0.25f, component.inverseSizeAndCos().y());
        assertEquals(0.125f, component.inverseSizeAndCos().z());
        assertEquals(0, component.inverseSizeAndCos().w(), 1.0e-6);
        assertEquals(1, component.uvScale().w(), 1.0e-6);
        assertEquals(2, component.uvScale().y());
        assertEquals(0.3f, component.uvOffset().z());
        assertEquals(0.03f, model.extinction());
        assertEquals(0.2f, model.phaseG1());
        assertEquals(0.3f, model.phaseG2());
        assertEquals(0.3f, model.phaseMix());
        assertEquals(7.437036f, model.ambientRadiance(), 1.0e-6);
        assertEquals(12.209689f, model.shadowFirstStep());
    }

    @Test void serializedPhaseOverridesOnlyItsExplicitAdapterFallback() throws Exception {
        var fixture = fixture();
        fixture.sky().put("HGPhaseFunction_G1[27]", 0.7);
        var model = CloudlyCloudPass.SourceModel.from(load(fixture));
        assertEquals(0.7f, model.phaseG1());
        assertEquals(0.3f, model.phaseG2());
        assertEquals(0.3f, model.phaseMix());
        assertEquals(0.2, fixture.adapter().get("phaseG1"));
    }

    @Test void missingNativeAndAdapterPhaseNeverInventsAValue() throws Exception {
        var fixture = fixture();
        fixture.adapter().remove("phaseG1");
        assertThrows(IllegalArgumentException.class, () -> CloudlyCloudPass.SourceModel.from(load(fixture)));
    }

    @Test void unsupportedRotationAndSourceRemapAreRejected() throws Exception {
        var fixture = fixture();
        fixture.component().put("Rotation[2]", xyz(0.1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CloudlyCloudPass.SourceModel.from(load(fixture)));
        fixture.component().put("Rotation[2]", xyz(0, 0, 0));
        fixture.component().put("DensityRemap[9]", Map.of("X", 0.2, "Y", 1));
        assertThrows(IllegalArgumentException.class, () -> CloudlyCloudPass.SourceModel.from(load(fixture)));
    }

    @Test void adapterRequiresExplicitHeightAndHandedness() throws Exception {
        var fixture = fixture();
        fixture.adapter().remove("heightOrigin");
        assertThrows(IllegalArgumentException.class, () -> CloudlyCloudPass.SourceModel.from(load(fixture)));
        fixture.adapter().put("heightOrigin", "hostSeaLevel");
        fixture.adapter().put("axisMap", "X,Z,Y");
        assertThrows(IllegalArgumentException.class, () -> CloudlyCloudPass.SourceModel.from(load(fixture)));
    }

    @Test void unsupportedSourceSharedShearIsRejectedBeforeGpuPreparation() throws Exception {
        var fixture = fixture();
        shared(fixture.component()).put("Shear[13]", xyz(0.5, 0, 0));
        CloudlySourcePack source = load(fixture);
        assertThrows(IllegalArgumentException.class, () -> CloudlyCloudPass.validateSource(source));
    }

    private CloudlySourcePack load(Fixture fixture) throws Exception {
        byte[] bytes = new byte[8];
        Files.write(directory.resolve("mip.bin"), bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Map<String, Object> mip = Map.of("level", 0, "width", 4, "height", 4, "depth", 1,
                "path", "mip.bin", "sha256", hash);
        Map<String, Object> texture = Map.of("textureId", 0, "sourceAsset", "/synthetic/volume", "format", "PF_DXT1",
                "mips", List.of(mip));
        Map<String, Object> manifest = Map.of("schemaVersion", 1, "source", Map.of(), "skyParameters", fixture.sky(),
                "rendererAdapter", fixture.adapter(), "components", List.of(fixture.group(), fixture.component()),
                "textures", List.of(texture));
        Path path = directory.resolve("manifest.json");
        Files.writeString(path, new Gson().toJson(manifest));
        return CloudlySourcePack.load(path);
    }

    private static Fixture fixture() {
        Map<String, Object> adapter = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("target", "Caustica"), Map.entry("approximation", true), Map.entry("densityChannel", 0),
                Map.entry("positionUnits", "metres"), Map.entry("sourceUpAxis", "Z"), Map.entry("axisMap", "X,Z,-Y"),
                Map.entry("heightOrigin", "hostSeaLevel"), Map.entry("scaleMeaning", "fullSize"),
                Map.entry("rotationMeaning", "eulerZRadians"), Map.entry("componentTransforms", "absolute"),
                Map.entry("densityOperation", "maxUnion"), Map.entry("densityRemap", "identity"),
                Map.entry("extinctionUnits", "inverseMetres"), Map.entry("ambientRadianceCdM2", 2),
                Map.entry("singleScatteringAlbedo", 0.95), Map.entry("phaseG1", 0.2), Map.entry("phaseG2", 0.3),
                Map.entry("phaseMixFactor", 0.3)));
        Map<String, Object> sky = new LinkedHashMap<>(Map.of(
                "SigmaCloudAsAlbedoExtinction[13]", Map.of("Extinction_[1]", Map.of("Factor", 0.03)),
                "CloudPhaseFunctionScale_HG[22]", 0.5,
                "CloudSkyAmbientLightIntensity[51]", 3.718518,
                "TraceParams[149]", Map.of("SunRayStepFirstLen[9]", 12.209689, "SunRayStepBase[12]", 1.906351),
                "CloudLayer0[177]", Map.of("CloudDensity[12]", 0.27759),
                "CloudLayer1[178]", Map.of("CloudDensity[12]", 0.405602),
                "CloudLayer2[179]", Map.of("CloudDensity[12]", 0.846053)));
        Map<String, Object> group = Map.of("IsGroup", true, "IsHidden[10]", false,
                "SharedData[11]", sharedData("Base", "Auto"));
        Map<String, Object> component = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("IsGroup", false), Map.entry("IsHidden[10]", false), Map.entry("TextureId[8]", 0),
                Map.entry("DensityOperation[7]", 0), Map.entry("DensityRemap[9]", Map.of("X", 0, "Y", 1)),
                Map.entry("Rotation[2]", xyz(0, 0, Math.PI / 2)), Map.entry("ParentId[15]", 0),
                Map.entry("Position[1]", xyz(10, 20, 30)), Map.entry("Scale[3]", xyz(2, 4, 8)),
                Map.entry("UVScale[4]", xyz(1, 2, 3)), Map.entry("UVOffset[5]", xyz(0.1, 0.2, 0.3)),
                Map.entry("SharedData[11]", sharedData("None", "Disable"))));
        return new Fixture(adapter, sky, group, component);
    }

    private static Map<String, Object> sharedData(String placement, String mode) {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("PlacementTypeOverride", "ECloudPlacementType::" + placement),
                Map.entry("PlacementMode[1]", "ECloudlyPlacementMode::" + mode),
                Map.entry("UserHeightOffsetInMeters[2]", 0), Map.entry("RainCloudStretch[3]", false),
                Map.entry("IsRainPillar[4]", false), Map.entry("RainPillarRadiusAsFraction[5]", 1),
                Map.entry("IsAbsorptionMask[6]", false), Map.entry("AbsorptionMaskRadiusAsFraction[7]", 1),
                Map.entry("StraightStretch[8]", false), Map.entry("StraightStretchStartTexcoordW[9]", 0.4),
                Map.entry("StraightStretchNumTexelsBleed[10]", 2), Map.entry("StraightStretchCoversEntireMap[11]", false),
                Map.entry("KeepDensity[12]", false), Map.entry("Shear[13]", xyz(0, 0, 0))));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shared(Map<String, Object> component) {
        return (Map<String, Object>) component.get("SharedData[11]");
    }
    private static Map<String, Object> xyz(double x, double y, double z) { return Map.of("X", x, "Y", y, "Z", z); }
    private record Fixture(Map<String, Object> adapter, Map<String, Object> sky,
                           Map<String, Object> group, Map<String, Object> component) { }
}
