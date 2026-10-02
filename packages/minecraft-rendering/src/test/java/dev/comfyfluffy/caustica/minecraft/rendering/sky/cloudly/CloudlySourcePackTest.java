package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.minecraft.rendering.TestResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class CloudlySourcePackTest {
    @Test void bc1DecodePreservesPaletteAlphaAndZPlanes() {
        byte[] blocks = {
                (byte) 0xff, (byte) 0xff, 0, 0, (byte) 0xe4, (byte) 0xe4, (byte) 0xe4, (byte) 0xe4,
                0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xe4, (byte) 0xe4, (byte) 0xe4, (byte) 0xe4
        };
        ByteBuffer voxels = ByteBuffer.allocate(4 * 4 * 2 * 4);
        CloudlySourcePack.decodeBc1(blocks, 4, 4, 2, voxels);
        assertEquals(voxels.capacity(), voxels.position());
        assertVoxel(voxels, 0, 255, 255);
        assertVoxel(voxels, 1, 0, 255);
        assertVoxel(voxels, 2, 170, 255);
        assertVoxel(voxels, 3, 85, 255);
        assertVoxel(voxels, 16, 0, 255);
        assertVoxel(voxels, 17, 255, 255);
        assertVoxel(voxels, 18, 127, 255);
        assertVoxel(voxels, 19, 0, 0);
    }

    @Test void bc1DecodeClipsPartialBlocksAndPreservesDestinationSlice() {
        byte[] block = {(byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0, 0};
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.putInt(0, 0x12345678);
        buffer.putInt(20, 0x76543210);
        CloudlySourcePack.decodeBc1(block, 2, 2, 1, buffer.slice(4, 16));
        assertEquals(0x12345678, buffer.getInt(0));
        assertEquals(0x76543210, buffer.getInt(20));
        for (int index = 4; index < 20; index++) assertEquals(255, buffer.get(index) & 255);
    }

    @Test void sourceMetadataAndAdapterStaySeparateAndImmutable(@TempDir Path directory) throws Exception {
        byte[] block = {(byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0, 0};
        Files.write(directory.resolve("mip.bc1"), block);
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, manifest("mip.bc1", hash(block), """
                ,"rendererAdapter":{"target":"Caustica","approximation":true,"densityChannel":"r"}
                """));
        CloudlySourcePack pack = CloudlySourcePack.load(manifest);
        assertEquals(new BigDecimal("1.25"), pack.components().getFirst().get("Position"));
        assertFalse(pack.components().getFirst().containsKey("DensityRemap"));
        assertFalse(pack.skyParameters().containsKey("densityChannel"));
        assertEquals("r", pack.rendererAdapter().get("densityChannel"));
        assertThrows(UnsupportedOperationException.class, () -> pack.components().getFirst().put("Position", 2));
        assertThrows(UnsupportedOperationException.class, () -> pack.rendererAdapter().put("densityChannel", "a"));
        ByteBuffer rgba = ByteBuffer.allocate(4 * 4 * 4);
        pack.textures().getFirst().mips().getFirst().decodeRgba8(rgba);
        assertVoxel(rgba, 0, 255, 255);
    }

    @Test void payloadHashAndLocalPathAreEnforced(@TempDir Path directory) throws Exception {
        Path packDirectory = Files.createDirectory(directory.resolve("pack"));
        byte[] block = {(byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0, 0};
        Files.write(directory.resolve("outside.bc1"), block);
        Path manifest = packDirectory.resolve("manifest.json");
        Files.writeString(manifest, manifest("../outside.bc1", hash(block), ""));
        assertThrows(IOException.class, () -> CloudlySourcePack.load(manifest));
        Files.write(packDirectory.resolve("mip.bc1"), block);
        Files.writeString(manifest, manifest("mip.bc1", "0".repeat(64), ""));
        CloudlySourcePack pack = CloudlySourcePack.load(manifest);
        assertThrows(IOException.class, () -> pack.textures().getFirst().mips().getFirst().readBc1());
        Files.writeString(manifest, manifest("mip.bc1", hash(block), ""));
        CloudlySourcePack valid = CloudlySourcePack.load(manifest);
        Files.write(packDirectory.resolve("mip.bc1"), new byte[9]);
        assertThrows(IOException.class, () -> valid.textures().getFirst().mips().getFirst().readBc1());
    }

    @Test void textureRevisionLivesUntilItsLastCapturedClaim() {
        AtomicInteger retired = new AtomicInteger();
        CloudlyCloudResources revision = new CloudlyCloudResources(null, Map.of(),
                new GpuDescriptorIndex.Sampler(3), TestResource.create(retired::incrementAndGet));
        var firstFrame = revision.retain();
        var secondFrame = revision.retain();
        revision.close();
        assertEquals(0, retired.get());
        firstFrame.close();
        assertEquals(0, retired.get());
        secondFrame.close();
        assertEquals(1, retired.get());
    }

    private static void assertVoxel(ByteBuffer buffer, int index, int rgb, int alpha) {
        for (int channel = 0; channel < 3; channel++) assertEquals(rgb, buffer.get(index * 4 + channel) & 255);
        assertEquals(alpha, buffer.get(index * 4 + 3) & 255);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String manifest(String payload, String hash, String adapter) {
        return """
                {"schemaVersion":1,"source":{"nativeDefaultValuesIncluded":false},
                 "skyParameters":{},"components":[{"Position":1.25}],
                 "textures":[{"textureId":0,"sourceAsset":"fixture","format":"PF_DXT1",
                   "mips":[{"level":0,"width":4,"height":4,"depth":1,"path":"%s","sha256":"%s"}]}]%s}
                """.formatted(payload, hash, adapter);
    }
}
