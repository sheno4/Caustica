package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.minecraft.rendering.TestResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

final class CloudlyFrameResourcesTest {
    @TempDir Path directory;

    @Test void allocationInspectionPreservesOriginalFormatsMipChainAndTypedArgumentBuffer() throws Exception {
        var schedule = schedule(fixture());
        var plan = CloudlyFrameResources.inspect(schedule, schedule.resolve(Map.of()));
        var image = plan.images().getFirst();
        assertEquals(8, image.width());
        assertEquals(4, image.height());
        assertEquals(3, image.mipLevels());
        assertEquals(VK_FORMAT_R16G16B16A16_SFLOAT, image.format());
        assertTrue(image.clearOnAllocation());
        var buffer = plan.buffers().getFirst();
        assertEquals(60, buffer.byteSize());
        assertEquals(4, buffer.strideBytes());
        assertEquals("STORAGE_TEXEL_BUFFER", buffer.descriptorType());
        assertEquals(VK_FORMAT_R32_UINT, buffer.format());
        assertNotEquals(0, buffer.usage() & VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        assertEquals(VK_SAMPLER_ADDRESS_MODE_REPEAT, plan.samplers().getFirst().addressU());
        assertThrows(UnsupportedOperationException.class, () -> plan.images().clear());
    }

    @Test void invalidSourceDeclarationsFailBeforeAnyGpuAllocation() throws Exception {
        JsonObject root = fixture();
        root.getAsJsonArray("images").get(0).getAsJsonObject().addProperty("mipLevels", 5);
        assertAllocationRejected(root);

        root = fixture();
        root.getAsJsonArray("images").get(0).getAsJsonObject().add("usage", strings("SAMPLED", "STORAGE"));
        assertAllocationRejected(root);

        root = fixture();
        root.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("strideBytes", 8);
        assertAllocationRejected(root);

        root = fixture();
        root.getAsJsonObject("samplers").getAsJsonObject("PointWrap").add("address", numbers(0, 0, 999));
        assertAllocationRejected(root);

        root = fixture();
        root.getAsJsonArray("stages").get(0).getAsJsonObject().add("resources", strings("Args", "ExternalSource"));
        assertAllocationRejected(root);
    }

    @Test void clearTargetsAndValuesAreValidatedAsOneOperation() throws Exception {
        JsonObject root = fixture();
        root.getAsJsonArray("stages").get(0).getAsJsonObject().add("resources", strings("Args", "Args"));
        assertAllocationRejected(root);
        root = fixture();
        root.getAsJsonArray("stages").get(0).getAsJsonObject().add("value", numbers(0, 1, 0, 0));
        assertAllocationRejected(root);
        root = fixture();
        root.getAsJsonArray("buffers").get(0).getAsJsonObject().add("usage", strings("STORAGE_TEXEL_BUFFER", "INDIRECT_BUFFER"));
        assertAllocationRejected(root);
    }

    @Test void descriptorsRequireMatchingHeapDimensionScalarAndStructuredStride() {
        var floatImage = image("Cloud", VK_FORMAT_R16G16B16A16_SFLOAT, 11);
        var uintImage = image("Ids", VK_FORMAT_R16G16_UINT, 21);
        var args = buffer("Args", 4, 15, VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                "STORAGE_TEXEL_BUFFER", VK_FORMAT_R32_UINT, 31);
        var structured = buffer("Tiles", 16, 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "STORAGE_BUFFER", VK_FORMAT_UNDEFINED, 41);
        try (var epoch = new CloudlyFrameResources(null, Map.of("Cloud", floatImage, "Ids", uintImage),
                Map.of("Args", args, "Tiles", structured), Map.of("PointWrap", new GpuDescriptorIndex.Sampler(51)), ResourceOwner.none())) {
            assertEquals(11, epoch.descriptor("Cloud", field("Resource", "Texture2D<float4>")).value());
            assertEquals(12, epoch.descriptor("Cloud", field("Resource", "RWTexture2D<float4>")).value());
            assertEquals(21, epoch.descriptor("Ids", field("Resource", "Texture2D<uint2>")).value());
            assertEquals(31, epoch.descriptor("Args", field("Resource", "RWBuffer<uint>")).value());
            assertEquals(31, epoch.descriptor("Args", field("Resource", "RWBuffer<uint,37>")).value());
            assertEquals(41, epoch.descriptor("Tiles", field("Resource", "RWStructuredBuffer<uint4>")).value());
            assertEquals(51, epoch.descriptor("PointWrap", field("Sampler", "SamplerState")).value());
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("Ids", field("Resource", "Texture2D<float2>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("Cloud", field("Resource", "Texture3D<float4>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("Cloud", field("Resource", "Texture2D<floatInvalid>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("Args", field("Resource", "RWStructuredBuffer<uint>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("Args", field("Resource", "RWBuffer<float>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("Tiles", field("Resource", "StructuredBuffer<uint>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("PointWrap", field("Resource", "Texture2D<float4>")));
            assertThrows(IllegalArgumentException.class, () -> epoch.descriptor("ExternalSource", field("Resource", "Texture2D<float4>")));
        }
    }

    @Test void indirectDispatchRequiresActualUsageAndAnEntireAlignedUint3() {
        var args = buffer("Args", 4, 15, VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                "STORAGE_TEXEL_BUFFER", VK_FORMAT_R32_UINT, 31);
        try (var epoch = new CloudlyFrameResources(null, Map.of(), Map.of("Args", args), Map.of(), ResourceOwner.none())) {
            assertSame(args, epoch.indirectBuffer("Args", 0));
            assertSame(args, epoch.indirectBuffer("Args", 48));
            assertThrows(IllegalArgumentException.class, () -> epoch.indirectBuffer("Args", 49));
            assertThrows(IllegalArgumentException.class, () -> epoch.indirectBuffer("Args", 52));
            assertThrows(IllegalArgumentException.class, () -> epoch.indirectBuffer("Args", -4));
            assertThrows(IllegalArgumentException.class, () -> epoch.indirectBuffer("Args", Long.MAX_VALUE));
        }
        var data = buffer("Data", 4, 15, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "STORAGE_BUFFER", 0, 32);
        assertThrows(IllegalArgumentException.class, () -> data.validateIndirectOffset(0));
    }

    @Test void capturedEpochSurvivesProducerCloseUntilItsReadersRetire() {
        AtomicInteger destroyed = new AtomicInteger();
        var original = new LinkedHashMap<>(Map.of("Cloud", image("Cloud", VK_FORMAT_R16G16B16A16_SFLOAT, 11)));
        var epoch = new CloudlyFrameResources(null, original, Map.of(), Map.of(), TestResource.create(destroyed::incrementAndGet));
        original.clear();
        assertEquals(1, epoch.images().size());
        assertThrows(UnsupportedOperationException.class, () -> epoch.images().clear());
        var reader = epoch.retain();
        epoch.close();
        epoch.close();
        assertEquals(0, destroyed.get());
        assertThrows(IllegalStateException.class, () -> epoch.image("Cloud"));
        assertThrows(IllegalStateException.class, epoch::retain);
        reader.close();
        assertEquals(1, destroyed.get());
    }

    @Test void clearsAndStageBarriersCannotRecordBeforeEpochInitialization() throws Exception {
        var schedule = schedule(fixture());
        try (var epoch = new CloudlyFrameResources(schedule, Map.of(), Map.of(), Map.of(), ResourceOwner.none())) {
            assertThrows(IllegalStateException.class, () -> epoch.recordClear(null, "ClearArgs"));
            assertThrows(IllegalStateException.class, () -> epoch.recordBarrier(null, "ClearArgs"));
        }
    }

    private void assertAllocationRejected(JsonObject root) throws Exception {
        var schedule = schedule(root);
        var resolved = schedule.resolve(Map.of());
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameResources.allocate(null, null, schedule, resolved));
    }

    private CloudlyFrameSchedule schedule(JsonObject root) throws Exception {
        Path path = directory.resolve("frame.json");
        Files.writeString(path, root.toString());
        return CloudlyFrameSchedule.load(path, null);
    }

    private static CloudlyShaderLibrary.BindingField field(String heap, String type) {
        return new CloudlyShaderLibrary.BindingField("value", heap, type, 0);
    }

    private static CloudlyFrameResources.Image image(String name, int format, int sampled) {
        return new CloudlyFrameResources.Image(name, "TWO_D", 8, 4, 1, 1, format,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, 1,
                new GpuDescriptorIndex.Resource(sampled), List.of(new GpuDescriptorIndex.Resource(sampled + 1)));
    }

    private static CloudlyFrameResources.Buffer buffer(String name, int stride, long count, int usage, String type, int format, int descriptor) {
        return new CloudlyFrameResources.Buffer(name, stride, count, stride * count, usage, type, format, 1,
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(256), stride * count), new GpuDescriptorIndex.Resource(descriptor));
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static JsonArray numbers(int... values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }

    private static JsonObject fixture() {
        return JsonParser.parseString("""
                {"schemaVersion":1,"executionReady":true,
                "images":[{"name":"History","dimension":"TWO_D","extent":[8,4],"format":97,"mipLevels":3,
                "usage":["SAMPLED","STORAGE","TRANSFER_DST"],"clearOnAllocation":[0,0,0,0],"lifetime":"PERSISTENT_FRAME_HISTORY"}],
                "buffers":[{"name":"Args","strideBytes":4,"elements":15,"descriptorType":"STORAGE_TEXEL_BUFFER","format":98,
                "usage":["STORAGE_TEXEL_BUFFER","INDIRECT_BUFFER","TRANSFER_DST"]}],
                "samplers":{"PointWrap":{"filter":0,"mipmapMode":0,"address":[0,0,0],"minLod":0,"maxLod":3.4028234663852886e38}},
                "externalDescriptors":[{"name":"ExternalSource","descriptorType":"SAMPLED_IMAGE"}],
                "stages":[{"name":"ClearArgs","operation":"CLEAR","dependsOn":[],"resources":["Args"],"value":[0,0,0,0]}]}
                """).getAsJsonObject();
    }
}
