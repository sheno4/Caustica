package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyShaderLibraryTest {
    @TempDir Path directory;

    @Test void sourcePushKeepsDeviceAddressesAndChannelSelectionIndependent() {
        var layout = new CloudlyShaderLibrary.PushLayout(24, List.of(
                new CloudlyShaderLibrary.PushField("parametersAddress", 0, 8),
                new CloudlyShaderLibrary.PushField("bindingsAddress", 8, 8),
                new CloudlyShaderLibrary.PushField("channelIndex", 20, 4)));
        ByteBuffer first = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer second = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        long address = 0xf1a2b3c4d5e60000L;
        layout.write(first, Map.of("parametersAddress", address, "bindingsAddress", address + 256, "channelIndex", 0L));
        layout.write(second, Map.of("parametersAddress", address, "bindingsAddress", address + 256, "channelIndex", 3L));
        assertEquals(address, first.getLong(0));
        assertEquals(address + 256, first.getLong(8));
        assertEquals(0, first.getInt(16));
        assertEquals(0, first.getInt(20));
        assertEquals(3, second.getInt(20));
        assertEquals(0, first.position());
    }

    @Test void missingFieldsAndOverlappingOffsetsCannotDispatch() {
        var layout = new CloudlyShaderLibrary.PushLayout(16, List.of(
                new CloudlyShaderLibrary.PushField("parametersAddress", 0, 8),
                new CloudlyShaderLibrary.PushField("bindingsAddress", 8, 8)));
        assertThrows(IllegalArgumentException.class, () -> layout.write(ByteBuffer.allocate(16), Map.of("parametersAddress", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new CloudlyShaderLibrary.PushLayout(16, List.of(
                new CloudlyShaderLibrary.PushField("parametersAddress", 0, 8),
                new CloudlyShaderLibrary.PushField("overlap", 4, 4))));
    }

    @Test void libraryVerifiesPrivateBinaryBeforeGpuAllocation() throws Exception {
        byte[] binary = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(1).putInt(0).array();
        Files.write(directory.resolve("private.spv"), binary);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        Path manifest = directory.resolve("library.json");
        Files.writeString(manifest, description(hash, "private.spv"));
        var library = CloudlyShaderLibrary.load(manifest);
        assertEquals(20, library.program("test").spirvBytes());
        assertEquals(256, library.program("test").parametersByteSize());
        binary[12] = 2;
        Files.write(directory.resolve("private.spv"), binary);
        assertThrows(java.io.IOException.class, () -> CloudlyShaderLibrary.load(manifest));
    }

    @Test void originalShaderReferencesStayInsidePrivateLibrary() throws Exception {
        Path manifest = directory.resolve("library.json");
        Files.writeString(manifest, description("0".repeat(64), "../outside.spv"));
        assertThrows(java.io.IOException.class, () -> CloudlyShaderLibrary.load(manifest));
    }

    @Test void reflectedCameraPatchesPreserveOtherSourceParameters() {
        var layout = new CloudlyShaderLibrary.ParameterLayout(64, Map.of(
                "View.camera", new CloudlyShaderLibrary.ParameterField("float3", 16, 12),
                "frameCount", new CloudlyShaderLibrary.ParameterField("uint", 4, 4)));
        ByteBuffer bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putFloat(0, 100000);
        layout.putFloats(bytes, "View.camera", 100, -200, 300);
        layout.putInts(bytes, "frameCount", 57);
        assertEquals(100000, bytes.getFloat(0));
        assertEquals(57, bytes.getInt(4));
        assertEquals(100, bytes.getFloat(16));
        assertEquals(-200, bytes.getFloat(20));
        assertEquals(300, bytes.getFloat(24));
        assertThrows(IllegalArgumentException.class, () -> layout.putFloats(bytes, "View.camera", 100));
        assertThrows(IllegalArgumentException.class, () -> layout.putInts(bytes, "View.camera", 100, 200, 300));
    }

    @Test void samplerBindingsCannotAccidentallyIndexTheResourceHeap() {
        var layout = new CloudlyShaderLibrary.BindingLayout(8, List.of(
                new CloudlyShaderLibrary.BindingField("density", "Resource", "Texture3D<float4>", 0),
                new CloudlyShaderLibrary.BindingField("filter", "Sampler", "SamplerState", 4)));
        var image = new dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource(123);
        var sampler = new dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Sampler(456);
        ByteBuffer bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        layout.write(bytes, Map.of("density", image, "filter", sampler));
        assertEquals(123, bytes.getInt(0));
        assertEquals(456, bytes.getInt(4));
        assertThrows(IllegalArgumentException.class,
                () -> layout.write(bytes, Map.of("density", image, "filter", image)));
    }

    private static String description(String hash, String path) {
        return """
                {"schemaVersion":1,"programs":[{"id":"test","entryPoint":"main",
                "spirv":"%s","sha256":"%s","parametersByteSize":256,"bindingByteSize":8,
                "pushByteSize":16,"sourceSha256":"%s","pushFields":[
                {"name":"parametersAddress","offset":0,"size":8},
                {"name":"bindingsAddress","offset":8,"size":8}]}]}
                """.formatted(path, hash, "1".repeat(64));
    }
}
