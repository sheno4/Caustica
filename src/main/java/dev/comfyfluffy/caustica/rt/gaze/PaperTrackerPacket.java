package dev.comfyfluffy.caustica.rt.gaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * PaperTracker gaze export v1 protocol constants, OSC control messages, and 72-byte packet decoder.
 */
public final class PaperTrackerPacket {
    public static final int DEFAULT_PORT = 9945;
    public static final long RENEW_INTERVAL_NS = 1_000_000_000L;
    public static final float PLANE_DISTANCE_METERS = 1.6f;
    public static final int PACKET_SIZE = 72;

    public static final int FLAG_LEFT_VALID = 1 << 0;
    public static final int FLAG_RIGHT_VALID = 1 << 1;
    public static final int FLAG_FUSED_VALID = 1 << 2;
    public static final int FLAG_LEFT_MEASURED = 1 << 3;
    public static final int FLAG_RIGHT_MEASURED = 1 << 4;
    public static final int FLAG_FUSED_MEASURED = 1 << 5;

    static final byte[] SUBSCRIBE_MESSAGE = encodeEmptyOscMessage("/papertracker/gaze/v1/subscribe");
    static final byte[] UNSUBSCRIBE_MESSAGE = encodeEmptyOscMessage("/papertracker/gaze/v1/unsubscribe");
    static final byte[] DATA_HEADER = encodeDataHeader();

    public record Sample(
            int sequence,
            int flags,
            int sampleAgeUs,
            float leftYawDeg,
            float leftPitchDeg,
            float rightYawDeg,
            float rightPitchDeg,
            float fusedYawDeg,
            float fusedPitchDeg) {
        public boolean leftValid() {
            return (flags & FLAG_LEFT_VALID) != 0;
        }

        public boolean rightValid() {
            return (flags & FLAG_RIGHT_VALID) != 0;
        }

        public boolean fusedValid() {
            return (flags & FLAG_FUSED_VALID) != 0;
        }
    }

    private PaperTrackerPacket() {
    }

    /** Decodes a 72-byte OSC {@code /papertracker/gaze/v1} datagram, returning {@code null} if invalid. */
    public static Sample decode(byte[] bytes, int length) {
        if (bytes == null || length != PACKET_SIZE || bytes.length < PACKET_SIZE) {
            return null;
        }
        return decode(ByteBuffer.wrap(bytes, 0, length));
    }

    /** Decodes from {@code buf.position()} to {@code buf.limit()}; does not assume array backing. */
    public static Sample decode(ByteBuffer buf) {
        if (buf == null || buf.remaining() != PACKET_SIZE) {
            return null;
        }
        int base = buf.position();
        for (int i = 0; i < DATA_HEADER.length; i++) {
            if (buf.get(base + i) != DATA_HEADER[i]) {
                return null;
            }
        }
        ByteOrder previousOrder = buf.order();
        try {
            buf.order(ByteOrder.BIG_ENDIAN);
            int argOffset = base + DATA_HEADER.length;
            int sequence = buf.getInt(argOffset);
            int flags = buf.getInt(argOffset + 4);
            int sampleAgeUs = buf.getInt(argOffset + 8);
            float leftYaw = buf.getFloat(argOffset + 12);
            float leftPitch = buf.getFloat(argOffset + 16);
            float rightYaw = buf.getFloat(argOffset + 20);
            float rightPitch = buf.getFloat(argOffset + 24);
            float fusedYaw = buf.getFloat(argOffset + 28);
            float fusedPitch = buf.getFloat(argOffset + 32);
            if (!validAngle(leftYaw) || !validAngle(leftPitch)
                    || !validAngle(rightYaw) || !validAngle(rightPitch)
                    || !validAngle(fusedYaw) || !validAngle(fusedPitch)) {
                return null;
            }
            return new Sample(sequence, flags, sampleAgeUs,
                    leftYaw, leftPitch, rightYaw, rightPitch, fusedYaw, fusedPitch);
        } finally {
            buf.order(previousOrder);
        }
    }

    private static boolean validAngle(float degrees) {
        return Float.isFinite(degrees) && Math.abs(degrees) < 90.0f;
    }

    private static byte[] encodeEmptyOscMessage(String address) {
        byte[] ascii = address.getBytes(StandardCharsets.US_ASCII);
        int paddedAddress = ((ascii.length + 4) / 4) * 4;
        byte[] out = new byte[paddedAddress + 4];
        System.arraycopy(ascii, 0, out, 0, ascii.length);
        out[paddedAddress] = ',';
        return out;
    }

    private static byte[] encodeDataHeader() {
        byte[] address = "/papertracker/gaze/v1".getBytes(StandardCharsets.US_ASCII);
        byte[] tags = ",iiiffffff".getBytes(StandardCharsets.US_ASCII);
        int paddedAddress = ((address.length + 4) / 4) * 4;
        int paddedTags = ((tags.length + 4) / 4) * 4;
        byte[] out = new byte[paddedAddress + paddedTags];
        System.arraycopy(address, 0, out, 0, address.length);
        System.arraycopy(tags, 0, out, paddedAddress, tags.length);
        return out;
    }
}
