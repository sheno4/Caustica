package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local source metadata and verified BC1 mip payloads, without inferred Cloudly field semantics. */
public final class CloudlySourcePack {
    private static final long MAX_MANIFEST_BYTES = 4L * 1024 * 1024;
    private static final long MAX_TEXTURE_RGBA_BYTES = 256L * 1024 * 1024;
    private static final int MAX_TEXTURES = 64;
    private final Path manifest;
    private final Map<String, Object> source;
    private final Map<String, Object> skyParameters;
    private final Map<String, Object> rendererAdapter;
    private final List<Map<String, Object>> components;
    private final List<Texture> textures;

    private CloudlySourcePack(Path manifest, Map<String, Object> source,
                              Map<String, Object> skyParameters,
                              Map<String, Object> rendererAdapter,
                              List<Map<String, Object>> components, List<Texture> textures) {
        this.manifest = manifest;
        this.source = source;
        this.skyParameters = skyParameters;
        this.rendererAdapter = rendererAdapter;
        this.components = List.copyOf(components);
        this.textures = List.copyOf(textures);
    }

    /** Loads schema version 1; every mip must resolve inside the manifest's real directory. */
    public static CloudlySourcePack load(Path manifest) throws IOException {
        Path actualManifest = Objects.requireNonNull(manifest, "manifest").toRealPath();
        long size = Files.size(actualManifest);
        if (size == 0 || size > MAX_MANIFEST_BYTES) {
            throw new IOException("Cloudly manifest size is outside 1.." + MAX_MANIFEST_BYTES + " bytes");
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(actualManifest)).getAsJsonObject();
            if (integer(root, "schemaVersion") != 1) throw new IOException("Unsupported Cloudly source-pack schema");
            Map<String, Object> source = object(root.getAsJsonObject("source"));
            Map<String, Object> sky = object(root.getAsJsonObject("skyParameters"));
            Map<String, Object> adapter = root.has("rendererAdapter")
                    ? object(root.getAsJsonObject("rendererAdapter")) : Map.of();
            if (!adapter.isEmpty() && (!"Caustica".equals(adapter.get("target"))
                    || !Boolean.TRUE.equals(adapter.get("approximation")))) {
                throw new IOException("Cloudly renderer adapter must name Caustica and declare approximation=true");
            }
            List<Map<String, Object>> components = new ArrayList<>();
            for (JsonElement component : root.getAsJsonArray("components")) {
                components.add(object(component.getAsJsonObject()));
            }
            JsonArray textureEntries = root.getAsJsonArray("textures");
            if (textureEntries.isEmpty() || textureEntries.size() > MAX_TEXTURES) {
                throw new IOException("Cloudly texture count is outside 1.." + MAX_TEXTURES);
            }
            Path directory = actualManifest.getParent();
            HashSet<Integer> ids = new HashSet<>();
            List<Texture> textures = new ArrayList<>();
            for (JsonElement entry : textureEntries) {
                JsonObject texture = entry.getAsJsonObject();
                int id = integer(texture, "textureId");
                if (id < 0 || !ids.add(id)) throw new IOException("Invalid or repeated Cloudly texture id " + id);
                if (!texture.get("format").getAsString().equals("PF_DXT1")) {
                    throw new IOException("Cloudly texture " + id + " is not PF_DXT1");
                }
                List<Mip> mips = new ArrayList<>();
                long rgbaBytes = 0;
                for (JsonElement mipEntry : texture.getAsJsonArray("mips")) {
                    JsonObject mip = mipEntry.getAsJsonObject();
                    int level = integer(mip, "level");
                    int width = integer(mip, "width");
                    int height = integer(mip, "height");
                    int depth = integer(mip, "depth");
                    if (level != mips.size() || width <= 0 || height <= 0 || depth <= 0) {
                        throw new IOException("Invalid Cloudly mip dimensions or level for texture " + id);
                    }
                    if (!mips.isEmpty()) {
                        Mip prior = mips.getLast();
                        if ((prior.width() == 1 && prior.height() == 1 && prior.depth() == 1)
                                || width != Math.max(1, prior.width() / 2)
                                || height != Math.max(1, prior.height() / 2)
                                || depth != Math.max(1, prior.depth() / 2)) {
                            throw new IOException("Cloudly texture " + id + " has an invalid mip chain");
                        }
                    }
                    String hash = mip.get("sha256").getAsString();
                    if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid mip SHA-256 for texture " + id);
                    Path relative = Path.of(mip.get("path").getAsString());
                    if (relative.getRoot() != null) throw new IOException("Cloudly mip paths must be relative");
                    Path resolved = directory.resolve(relative).normalize();
                    if (!resolved.startsWith(directory)) throw new IOException("Cloudly mip path leaves the source pack");
                    Path actual = resolved.toRealPath();
                    if (!actual.startsWith(directory)) throw new IOException("Cloudly mip link leaves the source pack");
                    Mip loaded = new Mip(level, width, height, depth, actual, hash);
                    if (Files.size(actual) != loaded.bc1ByteSize()) {
                        throw new IOException("Cloudly mip byte count does not match dimensions: " + relative);
                    }
                    rgbaBytes = Math.addExact(rgbaBytes, loaded.rgbaByteSize());
                    if (rgbaBytes > MAX_TEXTURE_RGBA_BYTES) {
                        throw new IOException("Decoded Cloudly texture " + id + " exceeds " + MAX_TEXTURE_RGBA_BYTES + " bytes");
                    }
                    mips.add(loaded);
                }
                if (mips.isEmpty()) throw new IOException("Cloudly texture " + id + " has no mip payloads");
                textures.add(new Texture(id, texture.get("sourceAsset").getAsString(), List.copyOf(mips)));
            }
            return new CloudlySourcePack(actualManifest, source, sky, adapter, components, textures);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException
                 | ArithmeticException | NullPointerException failure) {
            throw new IOException("Invalid Cloudly source pack " + actualManifest, failure);
        }
    }

    public Path manifest() { return manifest; }
    /** Deeply immutable values; numbers are BigDecimal and absent fields remain absent. */
    public Map<String, Object> source() { return source; }
    public Map<String, Object> skyParameters() { return skyParameters; }
    /** Separate compatibility choices; an absent adapter is represented by an empty map. */
    public Map<String, Object> rendererAdapter() { return rendererAdapter; }
    public List<Map<String, Object>> components() { return components; }
    public List<Texture> textures() { return textures; }

    public record Texture(int textureId, String sourceAsset, List<Mip> mips) {
        public Texture { mips = List.copyOf(mips); }
        public long rgbaByteSize() {
            return mips.stream().mapToLong(Mip::rgbaByteSize).reduce(0, Math::addExact);
        }
    }

    public record Mip(int level, int width, int height, int depth, Path path, String sha256) {
        public long bc1ByteSize() {
            return Math.multiplyExact(Math.multiplyExact(((long) width + 3) / 4,
                    ((long) height + 3) / 4), Math.multiplyExact((long) depth, 8));
        }
        public long rgbaByteSize() {
            return Math.multiplyExact(Math.multiplyExact((long) width, height), Math.multiplyExact((long) depth, 4));
        }
        /** Revalidates the resolved path, bytes, and hash immediately before decoding. */
        public byte[] readBc1() throws IOException {
            if (!path.toRealPath().equals(path) || Files.size(path) != bc1ByteSize()) {
                throw new IOException("Cloudly mip payload path or size changed: " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != bc1ByteSize()) throw new IOException("Cloudly mip payload size changed: " + path);
            try {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!actual.equals(sha256)) throw new IOException("Cloudly mip SHA-256 mismatch: " + path);
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
            return bytes;
        }

        /** Writes X-fastest RGBA8 voxels; each Z plane retains BC1's block-row order and alpha mode. */
        public void decodeRgba8(ByteBuffer output) throws IOException {
            decodeBc1(readBc1(), width, height, depth, output);
        }
    }

    static void decodeBc1(byte[] bytes, int width, int height, int depth, ByteBuffer output) {
        int required = Math.toIntExact(Math.multiplyExact(Math.multiplyExact((long) width, height), (long) depth * 4));
        if (output.remaining() != required) throw new IllegalArgumentException("BC1 decode destination size mismatch");
        int blocksX = (width + 3) / 4;
        int blocksY = (height + 3) / 4;
        if (bytes.length != (long) blocksX * blocksY * depth * 8) {
            throw new IllegalArgumentException("BC1 input size mismatch");
        }
        int[] palette = new int[4];
        int base = output.position();
        for (int z = 0; z < depth; z++) for (int by = 0; by < blocksY; by++) for (int bx = 0; bx < blocksX; bx++) {
            int block = ((z * blocksY + by) * blocksX + bx) * 8;
            int first = unsigned16(bytes, block);
            int second = unsigned16(bytes, block + 2);
            palette[0] = rgb565(first);
            palette[1] = rgb565(second);
            if (first > second) {
                palette[2] = interpolate(palette[0], palette[1], 2, 1, 3);
                palette[3] = interpolate(palette[0], palette[1], 1, 2, 3);
            } else {
                palette[2] = interpolate(palette[0], palette[1], 1, 1, 2);
                palette[3] = 0;
            }
            int indices = (bytes[block + 4] & 255) | ((bytes[block + 5] & 255) << 8)
                    | ((bytes[block + 6] & 255) << 16) | ((bytes[block + 7] & 255) << 24);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                int px = bx * 4 + x;
                int py = by * 4 + y;
                if (px >= width || py >= height) continue;
                int rgba = palette[(indices >>> (2 * (y * 4 + x))) & 3];
                int destination = base + ((z * height + py) * width + px) * 4;
                output.put(destination, (byte) rgba);
                output.put(destination + 1, (byte) (rgba >>> 8));
                output.put(destination + 2, (byte) (rgba >>> 16));
                output.put(destination + 3, (byte) (rgba >>> 24));
            }
        }
        output.position(base + required);
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
    private static int rgb565(int value) {
        int red = (value >>> 11) & 31;
        int green = (value >>> 5) & 63;
        int blue = value & 31;
        return ((red << 3) | (red >>> 2)) | (((green << 2) | (green >>> 4)) << 8)
                | (((blue << 3) | (blue >>> 2)) << 16) | 0xff000000;
    }
    private static int interpolate(int first, int second, int a, int b, int divisor) {
        int result = 0xff000000;
        for (int channel = 0; channel < 3; channel++) {
            int shift = channel * 8;
            int value = (((first >>> shift) & 255) * a + ((second >>> shift) & 255) * b) / divisor;
            result |= value << shift;
        }
        return result;
    }
    private static int integer(JsonObject object, String name) {
        return object.get(name).getAsBigDecimal().intValueExact();
    }
    private static Map<String, Object> object(JsonObject object) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> values.put(entry.getKey(), value(entry.getValue())));
        return Collections.unmodifiableMap(values);
    }
    private static Object value(JsonElement value) {
        if (value.isJsonNull()) return null;
        if (value.isJsonObject()) return object(value.getAsJsonObject());
        if (value.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            value.getAsJsonArray().forEach(entry -> values.add(value(entry)));
            return Collections.unmodifiableList(values);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return new BigDecimal(primitive.getAsString());
        return primitive.getAsString();
    }
}
