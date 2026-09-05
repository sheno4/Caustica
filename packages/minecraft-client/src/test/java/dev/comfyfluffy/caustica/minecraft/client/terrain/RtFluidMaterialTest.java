package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialImage;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureResource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTextureSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtFluidMaterialTest {
    private static final ResourceId STILL = MinecraftMaterialIds.LAVA;
    private static final ResourceId FLOW = ResourceId.of("minecraft", "block/lava_flow");

    @Test
    void eachFaceSelectsItsOwnAtlasTransformAndEmission() {
        var stillUv = new MaterialUv(0.125f, 0.25f, 16.0f, 16.0f);
        var flowUv = new MaterialUv(0.5f, 0.75f, 8.0f, 8.0f);
        var resources = List.of(resource(STILL, stillUv, 100.0f), resource(FLOW, flowUv, 200.0f));
        var capture = capture(resources);

        assertSame(capture, capture.getBuilder(STILL));
        var still = capture.faceMaterial;
        assertSame(capture, capture.getBuilder(FLOW));
        var flow = capture.faceMaterial;

        assertEquals(STILL, still.material());
        assertEquals(FLOW, flow.material());
        assertNotEquals(still.materialIndex(), flow.materialIndex());
        var record = capture.materials.records().get(flow.materialIndex());
        assertEquals(flowUv, record.baseColorUv());
        assertEquals(200.0f, record.emissionLuminanceCdM2());
        assertEquals(0.25f, (0.53125f - record.baseColorUv().u()) * record.baseColorUv().inverseDu());
        assertEquals(0.75f, (0.84375f - record.baseColorUv().v()) * record.baseColorUv().inverseDv());

        capture.getBuilder(STILL);
        assertEquals(still, capture.faceMaterial);
        assertEquals(stillUv, capture.materials.records().get(still.materialIndex()).baseColorUv());
        capture.reset();
        assertNull(capture.faceMaterial);
    }

    @Test
    void waterKeepsItsNamedBoundaryAcrossSpriteChanges() {
        var capture = capture(List.of());
        capture.water = true;
        capture.getBuilder(ResourceId.of("minecraft", "block/water_still"));
        var still = capture.faceMaterial;
        capture.getBuilder(ResourceId.of("minecraft", "block/water_flow"));

        assertEquals(still, capture.faceMaterial);
        assertEquals(MinecraftMaterialIds.WATER, capture.faceMaterial.material());
        assertEquals(MinecraftMaterialTopology.MEDIUM_BOUNDARY, capture.faceMaterial.topology());
        assertEquals(1.0f, capture.materials.records().get(still.materialIndex()).transmissionWeight());
    }

    private static RtTerrainMesher.FluidCapture capture(List<MaterialTextureResource> resources) {
        var capture = new RtTerrainMesher.FluidCapture();
        capture.materials = MinecraftMaterialLookup.compile(new ResourcePackEpoch(1), List.of(),
                resources, MinecraftMaterialPageCompiler.compile(resources));
        return capture;
    }

    private static MaterialTextureResource resource(ResourceId name, MaterialUv uv, float luminance) {
        return new MaterialTextureResource(name, MaterialTextureKind.SHARED_ATLAS,
                new MaterialTextureAnalysisSource(1, 1, 1, new MinecraftMaterialTextureSource(
                        () -> new Pixel(0xFFFF8000), () -> new Pixel(0xFE000000), null, false)),
                uv, true, false, true, OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR,
                1.5f, luminance);
    }

    private record Pixel(int color) implements MaterialImage {
        @Override public int width() { return 1; }
        @Override public int height() { return 1; }
        @Override public int argb(int x, int y) { return color; }
        @Override public void close() { }
    }
}
