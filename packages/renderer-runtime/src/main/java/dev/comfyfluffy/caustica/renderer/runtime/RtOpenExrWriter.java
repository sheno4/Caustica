package dev.comfyfluffy.caustica.renderer.runtime;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Uncompressed scanline OpenEXR writer for half-float screenshots and float diagnostic images.
 *
 * <p>Keeping this in Java makes screenshot capture self-contained; the offline toolchain is only needed
 * to inspect or process the resulting files.
 */
final class RtOpenExrWriter {
    private static final int EXR_MAGIC = 20_000_630;
    private static final int EXR_VERSION = 2;
    private static final int HALF = 1;
    private static final int FLOAT = 2;
    private static final int NO_COMPRESSION = 0;
    private static final DateTimeFormatter CAPTURE_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    private static final int[] CHANNEL_COMPONENT = {3, 2, 1, 0}; // A, B, G, R (lexicographic channel order)
    private static final String[] CHANNEL_NAMES = {"A", "B", "G", "R"};

    private RtOpenExrWriter() {
    }

    record Metadata(
            float preExposure,
            float residualExposure,
            float absoluteExposure,
            String exposureMode,
            float evScene,
            float evTarget,
            float evApplied,
            long frame
    ) {
        Metadata {
            Objects.requireNonNull(exposureMode, "exposureMode");
        }
    }

    /** Writes the renderer's bottom-up RGBA half samples with scene-linear color metadata. */
    static void write(Path output, int width, int height, short[] rgba, Metadata metadata) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rgba, "rgba");
        Objects.requireNonNull(metadata, "metadata");
        checkDimensions(width, height, rgba.length);
        writeScanlines(output, width, height, header(width, height, metadata), Short.BYTES, i -> rgba[i]);
    }

    /** Writes unmodified diagnostic samples as FLOAT channels; missing channels are supplied by the caller. */
    static void writeRaw(Path output, int width, int height, float[] rgba,
                         Map<String, String> metadata) throws IOException {
        checkDimensions(width, height, rgba.length);
        ByteArrayOutputStream attributes = header(width, height, FLOAT);
        for (var entry : metadata.entrySet()) {
            stringAttribute(attributes, entry.getKey(), entry.getValue());
        }
        attributes.write(0);
        writeScanlines(output, width, height, attributes.toByteArray(), Float.BYTES,
                i -> Float.floatToRawIntBits(rgba[i]));
    }

    private static void checkDimensions(int width, int height, int samples) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("EXR dimensions must be positive: " + width + "x" + height);
        }
        int expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (samples != expected) {
            throw new IllegalArgumentException("Expected " + expected + " RGBA samples, got " + samples);
        }
    }

    private static void writeScanlines(Path output, int width, int height, byte[] header,
                                       int sampleBytes, IntUnaryOperator sampleBits) throws IOException {
        var row = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(width, 4), sampleBytes))
                .order(ByteOrder.LITTLE_ENDIAN);
        long first = header.length + (long) height * Long.BYTES;
        long blockBytes = 2L * Integer.BYTES + row.capacity();
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output), 1 << 20)) {
            stream.write(header);
            for (int y = 0; y < height; y++) writeLongLe(stream, first + y * blockBytes);
            for (int y = 0; y < height; y++) {
                row.clear();
                // EXR stores top-down scanlines, with each channel's samples contiguous within a row.
                for (int component : CHANNEL_COMPONENT) {
                    int source = ((height - 1 - y) * width * 4) + component;
                    for (int x = 0; x < width; x++, source += 4) {
                        int bits = sampleBits.applyAsInt(source);
                        if (sampleBytes == Short.BYTES) row.putShort((short) bits);
                        else row.putInt(bits);
                    }
                }
                writeIntLe(stream, y);
                writeIntLe(stream, row.capacity());
                stream.write(row.array());
            }
        }
    }

    /** Starts a header; each caller appends its metadata and the terminating zero byte. */
    private static ByteArrayOutputStream header(int width, int height, int pixelType) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        writeIntLe(bytes, EXR_MAGIC);
        writeIntLe(bytes, EXR_VERSION);

        ByteArrayOutputStream channels = new ByteArrayOutputStream();
        for (String name : CHANNEL_NAMES) {
            writeCString(channels, name);
            writeIntLe(channels, pixelType);
            channels.write(0); // pLinear
            channels.write(0);
            channels.write(0);
            channels.write(0);
            writeIntLe(channels, 1); // xSampling
            writeIntLe(channels, 1); // ySampling
        }
        channels.write(0);
        attribute(bytes, "channels", "chlist", channels.toByteArray());
        attribute(bytes, "compression", "compression", new byte[]{NO_COMPRESSION});
        attribute(bytes, "dataWindow", "box2i", box2i(width, height));
        attribute(bytes, "displayWindow", "box2i", box2i(width, height));
        attribute(bytes, "lineOrder", "lineOrder", new byte[]{0});
        attribute(bytes, "pixelAspectRatio", "float", floats(1.0f));
        attribute(bytes, "screenWindowCenter", "v2f", floats(0.0f, 0.0f));
        attribute(bytes, "screenWindowWidth", "float", floats(1.0f));

        stringAttribute(bytes, "software", "Caustica");
        return bytes;
    }

    private static byte[] header(int width, int height, Metadata metadata) throws IOException {
        ByteArrayOutputStream bytes = header(width, height, HALF);

        // ACEScg/AP1 primaries and ACES white (D60). This is the standard EXR chromaticities attribute,
        // so color-managed applications do not have to infer the working space from the filename.
        attribute(bytes, "chromaticities", "chromaticities", floats(
                0.713f, 0.293f,
                0.165f, 0.830f,
                0.128f, 0.044f,
                0.32168f, 0.33767f));
        attribute(bytes, "adoptedNeutral", "v2f", floats(0.32168f, 0.33767f));

        OffsetDateTime now = OffsetDateTime.now();
        stringAttribute(bytes, "capDate", CAPTURE_DATE.format(now));
        // OpenEXR adds utcOffset to local capDate to recover UTC; Java's offset has the opposite sign.
        floatAttribute(bytes, "utcOffset", -now.getOffset().getTotalSeconds());
        stringAttribute(bytes, "comments",
                "Residual-exposed scene-linear ACEScg; before Look/LMT, ACES output transform, and UI");
        stringAttribute(bytes, "causticaColorSpace", "ACEScg (AP1/D60), scene-linear");
        stringAttribute(bytes, "causticaEncoding",
                "RGB = sceneLinear * preExposure * residualExposure");
        stringAttribute(bytes, "causticaRecovery", "sceneLinear = RGB / causticaAbsoluteExposure");
        floatAttribute(bytes, "causticaPreExposure", metadata.preExposure());
        floatAttribute(bytes, "causticaResidualExposure", metadata.residualExposure());
        floatAttribute(bytes, "causticaAbsoluteExposure", metadata.absoluteExposure());
        stringAttribute(bytes, "causticaExposureMode", metadata.exposureMode());
        finiteFloatAttribute(bytes, "causticaEvScene", metadata.evScene());
        finiteFloatAttribute(bytes, "causticaEvTarget", metadata.evTarget());
        finiteFloatAttribute(bytes, "causticaEvApplied", metadata.evApplied());
        stringAttribute(bytes, "causticaFrame", Long.toUnsignedString(metadata.frame()));
        bytes.write(0); // end of header attributes
        return bytes.toByteArray();
    }

    private static byte[] box2i(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        writeIntLe(bytes, 0);
        writeIntLe(bytes, 0);
        writeIntLe(bytes, width - 1);
        writeIntLe(bytes, height - 1);
        return bytes.toByteArray();
    }

    private static byte[] floats(float... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(values.length * Float.BYTES);
        for (float value : values) {
            writeIntLe(bytes, Float.floatToRawIntBits(value));
        }
        return bytes.toByteArray();
    }

    private static void floatAttribute(OutputStream output, String name, float value) throws IOException {
        attribute(output, name, "float", floats(value));
    }

    private static void finiteFloatAttribute(OutputStream output, String name, float value) throws IOException {
        if (Float.isFinite(value)) {
            floatAttribute(output, name, value);
        }
    }

    private static void stringAttribute(OutputStream output, String name, String value) throws IOException {
        attribute(output, name, "string", value.getBytes(StandardCharsets.UTF_8));
    }

    private static void attribute(OutputStream output, String name, String type, byte[] value) throws IOException {
        writeCString(output, name);
        writeCString(output, type);
        writeIntLe(output, value.length);
        output.write(value);
    }

    private static void writeCString(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        output.write(0);
    }

    private static void writeIntLe(OutputStream output, int value) throws IOException {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }

    private static void writeLongLe(OutputStream output, long value) throws IOException {
        writeIntLe(output, (int) value);
        writeIntLe(output, (int) (value >>> 32));
    }
}
