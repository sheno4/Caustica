package dev.comfyfluffy.caustica.rt.gaze;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperTrackerUdpReceiverTest {
    @Test
    void subscribesReceivesBurstKeepsNewestAndUnsubscribes() throws Exception {
        AtomicLong clockNs = new AtomicLong(1_000_000_000L);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (DatagramSocket server = new DatagramSocket(0, loopback);
             DatagramSocket wrongPort = new DatagramSocket(0, loopback);
             PaperTrackerUdpReceiver receiver = new PaperTrackerUdpReceiver(clockNs::get)) {
            server.setSoTimeout(500);
            int port = server.getLocalPort();

            assertNull(receiver.poll(port));
            assertTrue(receiver.isOpen());

            DatagramPacket sub = new DatagramPacket(new byte[64], 64);
            server.receive(sub);
            assertEquals(PaperTrackerPacket.SUBSCRIBE_MESSAGE.length, sub.getLength());
            byte[] subBytes = new byte[sub.getLength()];
            System.arraycopy(sub.getData(), sub.getOffset(), subBytes, 0, sub.getLength());
            assertArrayEquals(PaperTrackerPacket.SUBSCRIBE_MESSAGE, subBytes);

            // Wrong source port must be ignored.
            byte[] spoof = PaperTrackerPacketTest.buildPacket(999, PaperTrackerPacket.FLAG_FUSED_VALID,
                    0, 20f, 0f, 20f, 0f, 20f, 0f);
            wrongPort.send(new DatagramPacket(spoof, spoof.length, loopback, receiver.localPort()));

            // Burst > 128 packets: receiver drains up to 128 per poll without throwing.
            for (int seq = 1; seq <= 140; seq++) {
                byte[] pkt = PaperTrackerPacketTest.buildPacket(seq, PaperTrackerPacket.FLAG_FUSED_VALID,
                        seq, 1f, 2f, 1f, 2f, 1f, 2f);
                server.send(new DatagramPacket(pkt, pkt.length, sub.getAddress(), sub.getPort()));
            }

            PaperTrackerPacket.Sample first = receiver.poll(port);
            assertNotNull(first);
            assertTrue(receiver.isConnected());
            assertTrue(first.sequence() >= 1 && first.sequence() <= 140);

            PaperTrackerPacket.Sample drained = receiver.poll(port);
            assertNotNull(drained);
            assertEquals(140, drained.sequence());

            // Advance 1 second to trigger renewal.
            clockNs.addAndGet(PaperTrackerPacket.RENEW_INTERVAL_NS);
            receiver.poll(port);
            DatagramPacket renew = new DatagramPacket(new byte[64], 64);
            server.receive(renew);
            assertEquals(PaperTrackerPacket.SUBSCRIBE_MESSAGE.length, renew.getLength());

            receiver.close();
            assertFalse(receiver.isOpen());
            DatagramPacket unsub = new DatagramPacket(new byte[64], 64);
            server.receive(unsub);
            assertEquals(PaperTrackerPacket.UNSUBSCRIBE_MESSAGE.length, unsub.getLength());
            byte[] unsubBytes = new byte[unsub.getLength()];
            System.arraycopy(unsub.getData(), unsub.getOffset(), unsubBytes, 0, unsub.getLength());
            assertArrayEquals(PaperTrackerPacket.UNSUBSCRIBE_MESSAGE, unsubBytes);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("Timed out waiting for OSC control message", e);
        }
    }
}
