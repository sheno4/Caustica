package dev.comfyfluffy.caustica.rt.gaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperTrackerPacketTest {
    @Test
    void buildsSpecCompliantOscControlMessages() {
        byte[] expectedSub = "/papertracker/gaze/v1/subscribe\0,\0\0\0".getBytes(StandardCharsets.US_ASCII);
        byte[] expectedUnsub = "/papertracker/gaze/v1/unsubscribe\0\0\0,\0\0\0".getBytes(StandardCharsets.US_ASCII);
        assertEquals(36, PaperTrackerPacket.SUBSCRIBE_MESSAGE.length);
        assertEquals(40, PaperTrackerPacket.UNSUBSCRIBE_MESSAGE.length);
        assertArrayEquals(expectedSub, PaperTrackerPacket.SUBSCRIBE_MESSAGE);
        assertArrayEquals(expectedUnsub, PaperTrackerPacket.UNSUBSCRIBE_MESSAGE);
    }

    @Test
    void decodesValidBigEndian72BytePacket() {
        byte[] packet = buildPacket(42, PaperTrackerPacket.FLAG_LEFT_VALID | PaperTrackerPacket.FLAG_FUSED_VALID,
                1250, -12.5f, 4.25f, 10.0f, -3.5f, -1.25f, 0.75f);

        PaperTrackerPacket.Sample sample = PaperTrackerPacket.decode(packet, packet.length);
        assertNotNull(sample);
        assertEquals(42, sample.sequence());
        assertTrue(sample.leftValid());
        assertFalse(sample.rightValid());
        assertTrue(sample.fusedValid());
        assertEquals(1250, sample.sampleAgeUs());
        assertEquals(-12.5f, sample.leftYawDeg());
        assertEquals(4.25f, sample.leftPitchDeg());
        assertEquals(10.0f, sample.rightYawDeg());
        assertEquals(-3.5f, sample.rightPitchDeg());
        assertEquals(-1.25f, sample.fusedYawDeg());
        assertEquals(0.75f, sample.fusedPitchDeg());
    }

    @Test
    void rejectsInvalidLengthsHeadersAndOutOfRangeAngles() {
        byte[] valid = buildPacket(1, PaperTrackerPacket.FLAG_FUSED_VALID, 0, 1f, 2f, 3f, 4f, 5f, 6f);
        assertNull(PaperTrackerPacket.decode(valid, 71));
        assertNull(PaperTrackerPacket.decode(new byte[73], 73));

        byte[] badHeader = valid.clone();
        badHeader[25] = 'x';
        assertNull(PaperTrackerPacket.decode(badHeader, badHeader.length));

        // Even an invalid eye's out-of-range angle must reject the packet.
        assertNull(PaperTrackerPacket.decode(buildPacket(2, 0, 0, 90.0f, 0f, 0f, 0f, 0f, 0f), 72));
        assertNull(PaperTrackerPacket.decode(buildPacket(3, 0, 0, 0f, -90.0f, 0f, 0f, 0f, 0f), 72));
        assertNull(PaperTrackerPacket.decode(buildPacket(4, 0, 0, 0f, 0f, Float.NaN, 0f, 0f, 0f), 72));
        assertNull(PaperTrackerPacket.decode(buildPacket(5, 0, 0, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 0f), 72));
    }

    static byte[] buildPacket(
            int sequence,
            int flags,
            int ageUs,
            float ly,
            float lp,
            float ry,
            float rp,
            float fy,
            float fp) {
        ByteBuffer buf = ByteBuffer.allocate(PaperTrackerPacket.PACKET_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(PaperTrackerPacket.DATA_HEADER);
        buf.putInt(sequence);
        buf.putInt(flags);
        buf.putInt(ageUs);
        buf.putFloat(ly);
        buf.putFloat(lp);
        buf.putFloat(ry);
        buf.putFloat(rp);
        buf.putFloat(fy);
        buf.putFloat(fp);
        return buf.array();
    }
}
