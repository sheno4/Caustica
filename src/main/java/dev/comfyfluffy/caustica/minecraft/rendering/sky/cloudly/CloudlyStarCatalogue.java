package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Original FStarData records: right ascension, declination, visual magnitude and temperature. */
public record CloudlyStarCatalogue(int count, byte[] data) {
    public CloudlyStarCatalogue { data = data.clone(); }
    @Override public byte[] data() { return data.clone(); }

    public static CloudlyStarCatalogue load(Path manifest) throws IOException {
        Path actual = manifest.toRealPath();
        var root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        int count = root.get("count").getAsInt();
        if (root.get("schemaVersion").getAsInt() != 1 || root.get("strideBytes").getAsInt() != 16
                || count < 1 || count > 1_000_000) throw new IOException("Invalid original star catalogue layout");
        Path file = actual.getParent().resolve(root.get("file").getAsString()).normalize();
        if (!file.startsWith(actual.getParent())) throw new IOException("Star catalogue leaves its source directory");
        byte[] data = Files.readAllBytes(file);
        if (data.length != Math.multiplyExact(count, 16)) throw new IOException("Star catalogue record count differs");
        try {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!sha.equals(root.get("sha256").getAsString())) throw new IOException("Star catalogue digest differs");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        ByteBuffer values = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            for (int field = 0; field < 4; field++) {
                float value = values.getFloat();
                if (!Float.isFinite(value) || field == 3 && value <= 0) {
                    throw new IOException("Invalid original star catalogue value at record " + i);
                }
            }
        }
        return new CloudlyStarCatalogue(count, data);
    }
}
