package dev.comfyfluffy.caustica.renderer.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class RtOpenExrRawTest {
    @TempDir Path directory;

    @Test void preservesFloatBitsAndFlipsRowsWithoutColorMetadata() throws Exception {
        Path output = directory.resolve("depth.exr");
        float nan = Float.intBitsToFloat(0x7fc01234);
        RtOpenExrWriter.writeRaw(output, 1, 2,
                new float[]{100000, -0.0f, Float.POSITIVE_INFINITY, nan, 2, 3, 4, 5},
                Map.of("causticaFrame", "42", "causticaEncoding", "raw depth"));
        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(20000630, file.getInt());
        assertEquals(2, file.getInt());
        Map<String, byte[]> attributes = new HashMap<>();
        while (true) {
            String name = cstring(file);
            if (name.isEmpty()) break;
            cstring(file);
            byte[] value = new byte[file.getInt()];
            file.get(value);
            attributes.put(name, value);
        }
        assertFalse(attributes.containsKey("chromaticities"));
        assertEquals("42", new String(attributes.get("causticaFrame"), StandardCharsets.UTF_8));
        ByteBuffer channels = ByteBuffer.wrap(attributes.get("channels")).order(ByteOrder.LITTLE_ENDIAN);
        for (String name : new String[]{"A", "B", "G", "R"}) {
            assertEquals(name, cstring(channels));
            assertEquals(2, channels.getInt());
            channels.position(channels.position() + 12);
        }
        long first = file.getLong();
        long second = file.getLong();
        assertEquals(file.position(), first);
        assertEquals(first + 24, second);
        assertEquals(0, file.getInt());
        assertEquals(16, file.getInt());
        for (float value : new float[]{5, 4, 3, 2}) assertEquals(Float.floatToRawIntBits(value), file.getInt());
        assertEquals(1, file.getInt());
        assertEquals(16, file.getInt());
        for (float value : new float[]{nan, Float.POSITIVE_INFINITY, -0.0f, 100000}) {
            assertEquals(Float.floatToRawIntBits(value), file.getInt());
        }
        assertFalse(file.hasRemaining());
    }

    private static String cstring(ByteBuffer buffer) {
        var text = new StringBuilder();
        byte value;
        while ((value = buffer.get()) != 0) text.append((char) value);
        return text.toString();
    }
}
