package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.minecraft.rendering.TestResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class CloudlyFrameTexturesTest {
    @Test void bc6CubeTailsPreserveSixFullBlocksAndExactMipOffsets(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory, CloudlyFrameTextures.Kind.CUBE, CloudlyFrameTextures.Format.BC6H, 4, 3);
        var source = CloudlyFrameTextures.load(fixture.path(), hash(Files.readAllBytes(fixture.path())));
        var texture = source.textures().getFirst();
        assertEquals(CloudlyFrameTextures.Kind.CUBE, texture.kind());
        assertEquals(3, texture.mips().size());
        assertEquals(288, texture.byteSize());
        assertArrayEquals(new long[]{0, 96, 192}, CloudlyFrameTextures.mipOffsets(texture));
        for (var mip : texture.mips()) {
            assertEquals(16, mip.faceBytes());
            assertEquals(96, mip.bytes().remaining());
            assertArrayEquals(Files.readAllBytes(directory.resolve("mip-" + mip.level() + ".bin")), bytes(mip.bytes()));
        }
        assertEquals(2, texture.mips().get(1).width());
        assertEquals(1, texture.mips().getLast().width());
    }

    @Test void capturedMipBytesRemainImmutableAfterFileChanges(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory, CloudlyFrameTextures.Kind.CUBE, CloudlyFrameTextures.Format.RGBA16F, 2, 2);
        var captured = CloudlyFrameTextures.load(fixture.path());
        var first = captured.textures().getFirst().mips().getFirst();
        byte[] original = bytes(first.bytes());
        Files.write(directory.resolve("mip-0.bin"), new byte[original.length]);
        assertArrayEquals(original, bytes(first.bytes()));
        assertThrows(ReadOnlyBufferException.class, () -> first.bytes().put(0, (byte) 7));
        first.bytes().position(4);
        assertEquals(0, first.bytes().position());
        assertThrows(UnsupportedOperationException.class, () -> captured.textures().clear());
        assertThrows(IOException.class, () -> CloudlyFrameTextures.load(fixture.path()));
    }

    @Test void hashContainmentFaceRegionsAndCubeShapeAreRequired(@TempDir Path directory) throws Exception {
        Path pack = Files.createDirectory(directory.resolve("pack"));
        Fixture fixture = fixture(pack, CloudlyFrameTextures.Kind.CUBE, CloudlyFrameTextures.Format.BC6H, 4, 1);
        assertThrows(IOException.class, () -> CloudlyFrameTextures.load(fixture.path(), "0".repeat(64)));
        JsonObject texture = fixture.json().getAsJsonArray("textures").get(0).getAsJsonObject();
        JsonObject mip = texture.getAsJsonArray("mips").get(0).getAsJsonObject();
        Files.write(directory.resolve("outside.bin"), Files.readAllBytes(pack.resolve("mip-0.bin")));
        mip.addProperty("path", "../outside.bin");
        fixture.save();
        assertThrows(IOException.class, () -> CloudlyFrameTextures.load(fixture.path()));
        mip.addProperty("path", "mip-0.bin");
        JsonObject face = mip.getAsJsonArray("faces").get(1).getAsJsonObject();
        face.addProperty("bufferOffset", 15);
        fixture.save();
        assertThrows(IOException.class, () -> CloudlyFrameTextures.load(fixture.path()));
        face.addProperty("bufferOffset", 16);
        texture.addProperty("arrayLayers", 1);
        fixture.save();
        assertThrows(IOException.class, () -> CloudlyFrameTextures.load(fixture.path()));
        texture.addProperty("arrayLayers", 6);
        texture.getAsJsonObject("descriptor").addProperty("viewType", "VK_IMAGE_VIEW_TYPE_3D");
        fixture.save();
        assertThrows(IOException.class, () -> CloudlyFrameTextures.load(fixture.path()));
    }

    @Test void completedRevisionTypesAndOwnersRemainWithCapturedReaders(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory, CloudlyFrameTextures.Kind.TWO_D, CloudlyFrameTextures.Format.BGRA8, 2, 1);
        var source = CloudlyFrameTextures.load(fixture.path());
        var spec = source.textures().getFirst();
        var image = new CloudlyFrameTextures.Image2D(spec, new GpuDescriptorIndex.Resource(7), new GpuDescriptorIndex.Sampler(8));
        AtomicInteger retired = new AtomicInteger();
        var ready = new CloudlyFrameTextures.Ready(source, Map.of("fixture", image), TestResource.create(retired::incrementAndGet));
        assertSame(image, ready.image2D("fixture"));
        assertEquals(7, ready.descriptors().get("NativeBinding").value());
        assertEquals(8, ready.image2D("fixture").samplerIndex().value());
        assertThrows(IllegalArgumentException.class, () -> ready.cube("fixture"));
        assertThrows(IllegalArgumentException.class, () -> new CloudlyFrameTextures.Cube(spec, image.sampledIndex(), image.samplerIndex()));
        assertThrows(UnsupportedOperationException.class, () -> ready.descriptors().clear());
        var frame = ready.retain();
        ready.close();
        assertEquals(0, retired.get());
        frame.close();
        assertEquals(1, retired.get());
    }

    @Test void bcLogicalDeviceContractIsEnforcedBeforeGpuAllocation(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory, CloudlyFrameTextures.Kind.CUBE, CloudlyFrameTextures.Format.BC6H, 4, 1);
        var source = CloudlyFrameTextures.load(fixture.path());
        assertThrows(UnsupportedOperationException.class, () -> CloudlyFrameTextures.prepare(null, null, null, source, Map.of(), false, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameTextures.prepare(null, null, null, source, Map.of(), true, ignored -> { }));
    }

    private static byte[] bytes(ByteBuffer buffer) { byte[] data = new byte[buffer.remaining()]; buffer.get(data); return data; }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static JsonArray vector(int... values) {
        JsonArray array = new JsonArray(); for (int value : values) array.add(value); return array;
    }

    private static Fixture fixture(Path directory, CloudlyFrameTextures.Kind kind, CloudlyFrameTextures.Format format,
                                    int width, int levels) throws Exception {
        String nativeFormat = switch (format) {
            case BGRA8 -> "PF_B8G8R8A8";
            case RGBA16F -> "PF_FloatRGBA";
            case BC6H -> "PF_BC6H";
        };
        JsonObject texture = new JsonObject();
        texture.addProperty("resource", "fixture");
        texture.addProperty("nativeClass", kind == CloudlyFrameTextures.Kind.CUBE ? "TextureCube" : "Texture2D");
        texture.addProperty("nativePixelFormat", nativeFormat);
        texture.addProperty("width", width); texture.addProperty("height", width); texture.addProperty("depth", 1);
        texture.addProperty("arrayLayers", kind.layers()); texture.addProperty("firstMip", 0); texture.addProperty("mipLevels", levels);
        JsonArray aliases = new JsonArray(); aliases.add("NativeBinding"); texture.add("bindingAliases", aliases);
        JsonArray faceOrder = new JsonArray();
        if (kind == CloudlyFrameTextures.Kind.CUBE) List.of("+X", "-X", "+Y", "-Y", "+Z", "-Z").forEach(faceOrder::add);
        texture.add("faceOrder", faceOrder);
        JsonObject vulkan = new JsonObject(); vulkan.addProperty("value", format.vulkan()); texture.add("vulkanFormat", vulkan);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("kind", kind == CloudlyFrameTextures.Kind.CUBE ? "sampledImageCubeDescriptor" : "sampledImage2DDescriptor");
        descriptor.addProperty("format", format.vulkan()); descriptor.addProperty("layerCount", kind.layers());
        descriptor.addProperty("levelCount", levels); descriptor.addProperty("baseMipLevel", 0); descriptor.addProperty("baseArrayLayer", 0);
        descriptor.addProperty("imageType", "VK_IMAGE_TYPE_2D");
        descriptor.addProperty("viewType", kind == CloudlyFrameTextures.Kind.CUBE ? "VK_IMAGE_VIEW_TYPE_CUBE" : "VK_IMAGE_VIEW_TYPE_2D");
        descriptor.add("imageExtent", vector(width, width, 1)); texture.add("descriptor", descriptor);
        JsonArray mips = new JsonArray(); long payloadBytes = 0;
        for (int level = 0; level < levels; level++) {
            int size = Math.max(1, width >> level), faceBytes = Math.toIntExact(format.faceByteSize(size, size));
            byte[] data = new byte[faceBytes * kind.layers()];
            for (int offset = 0; offset < data.length; offset++) data[offset] = (byte) (offset + level + 1);
            Files.write(directory.resolve("mip-" + level + ".bin"), data);
            JsonObject mip = new JsonObject();
            mip.addProperty("level", level); mip.addProperty("width", size); mip.addProperty("height", size); mip.addProperty("depth", 1);
            mip.addProperty("arrayLayers", kind.layers()); mip.addProperty("bytes", data.length); mip.addProperty("path", "mip-" + level + ".bin");
            mip.addProperty("sha256", hash(data)); JsonArray faces = new JsonArray();
            for (int layer = 0; layer < kind.layers(); layer++) {
                JsonObject face = new JsonObject();
                face.addProperty("layer", layer); face.addProperty("face", kind == CloudlyFrameTextures.Kind.CUBE ? faceOrder.get(layer).getAsString() : "2D");
                face.addProperty("bufferOffset", layer * faceBytes); face.addProperty("byteCount", faceBytes); face.addProperty("rowPitchBytes", format.rowPitch(size));
                face.addProperty("sha256", hash(java.util.Arrays.copyOfRange(data, layer * faceBytes, (layer + 1) * faceBytes)));
                JsonObject copy = new JsonObject();
                copy.addProperty("bufferOffset", layer * faceBytes); copy.addProperty("mipLevel", level); copy.addProperty("baseArrayLayer", layer);
                copy.addProperty("layerCount", 1); copy.addProperty("bufferRowLength", 0); copy.addProperty("bufferImageHeight", 0);
                copy.add("imageOffset", vector(0, 0, 0)); copy.add("imageExtent", vector(size, size, 1));
                face.add("copyRegion", copy); faces.add(face);
            }
            mip.add("faces", faces); mips.add(mip); payloadBytes += data.length;
        }
        texture.add("mips", mips); texture.addProperty("payloadBytes", payloadBytes);
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1);
        JsonArray textures = new JsonArray(); textures.add(texture); root.add("textures", textures);
        Fixture fixture = new Fixture(directory.resolve("manifest.json"), root); fixture.save(); return fixture;
    }
    private record Fixture(Path path, JsonObject json) {
        void save() throws IOException { Files.writeString(path, json.toString()); }
    }
}
