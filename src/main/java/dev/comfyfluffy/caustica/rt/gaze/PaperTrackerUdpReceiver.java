package dev.comfyfluffy.caustica.rt.gaze;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Non-blocking IPv4 loopback UDP subscriber for PaperTracker's OSC gaze export.
 * Designed for single-thread polling on the render thread.
 */
public final class PaperTrackerUdpReceiver implements AutoCloseable {
    private static final int MAX_DATAGRAMS_PER_POLL = 128;
    private static final long RETRY_OPEN_INTERVAL_NS = 1_000_000_000L;
    private static final byte[] LOOPBACK_BYTES = {127, 0, 0, 1};

    private final LongSupplier clockNanos;
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(PaperTrackerPacket.PACKET_SIZE + 1);
    private final ByteBuffer subscribeBuffer = ByteBuffer.wrap(PaperTrackerPacket.SUBSCRIBE_MESSAGE);

    private DatagramChannel channel;
    private InetSocketAddress targetAddress;
    private int currentPort;
    private long nextOpenNanos;
    private long nextRenewNanos;
    private boolean connected;
    private PaperTrackerPacket.Sample latest;
    private String lastError;

    public PaperTrackerUdpReceiver() {
        this(System::nanoTime);
    }

    public PaperTrackerUdpReceiver(LongSupplier clockNanos) {
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
    }

    public boolean isOpen() {
        return channel != null && channel.isOpen();
    }

    public boolean isConnected() {
        return connected;
    }

    public String lastError() {
        return lastError;
    }

    public int localPort() {
        if (channel == null) {
            return 0;
        }
        try {
            if (channel.getLocalAddress() instanceof InetSocketAddress isa) {
                return isa.getPort();
            }
        } catch (IOException ignored) {
        }
        return 0;
    }

    /**
     * Renews the subscription if due, drains up to 128 datagrams, and returns the newest valid sample
     * from {@code 127.0.0.1:port} (or {@code null} when none has arrived or the publisher went away).
     */
    public PaperTrackerPacket.Sample poll(int port) {
        if (port != currentPort) {
            close();
            currentPort = port;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        long now = clockNanos.getAsLong();
        if (channel == null) {
            if (now < nextOpenNanos) {
                return null;
            }
            if (!openChannel(port)) {
                nextOpenNanos = now + RETRY_OPEN_INTERVAL_NS;
                return null;
            }
        }
        if (now >= nextRenewNanos) {
            sendControlMessage(subscribeBuffer);
            nextRenewNanos = now + PaperTrackerPacket.RENEW_INTERVAL_NS;
        }
        for (int i = 0; i < MAX_DATAGRAMS_PER_POLL; i++) {
            receiveBuffer.clear();
            SocketAddress sender;
            try {
                sender = channel.receive(receiveBuffer);
            } catch (PortUnreachableException unreachable) {
                clearPublisherState();
                continue;
            } catch (SocketException socketEx) {
                if (isConnectionReset(socketEx)) {
                    clearPublisherState();
                    continue;
                }
                failChannel(port, now, socketEx);
                return null;
            } catch (IOException ioEx) {
                failChannel(port, now, ioEx);
                return null;
            }
            if (sender == null) {
                break;
            }
            if (!(sender instanceof InetSocketAddress isa)
                    || !(isa.getAddress() instanceof Inet4Address addr)
                    || !addr.isLoopbackAddress()
                    || isa.getPort() != port) {
                continue;
            }
            receiveBuffer.flip();
            PaperTrackerPacket.Sample decoded = PaperTrackerPacket.decode(receiveBuffer);
            if (decoded != null) {
                latest = decoded;
                connected = true;
            }
        }
        return connected ? latest : null;
    }

    @Override
    public void close() {
        if (channel != null) {
            if (targetAddress != null) {
                sendControlMessage(ByteBuffer.wrap(PaperTrackerPacket.UNSUBSCRIBE_MESSAGE));
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
        targetAddress = null;
        currentPort = 0;
        nextOpenNanos = 0L;
        nextRenewNanos = 0L;
        connected = false;
        latest = null;
        lastError = null;
    }

    private boolean openChannel(int port) {
        DatagramChannel opened = null;
        try {
            InetAddress loopback = InetAddress.getByAddress(LOOPBACK_BYTES);
            opened = DatagramChannel.open(StandardProtocolFamily.INET);
            opened.configureBlocking(false);
            opened.bind(new InetSocketAddress(loopback, 0));
            this.channel = opened;
            this.targetAddress = new InetSocketAddress(loopback, port);
            this.lastError = null;
            this.nextRenewNanos = 0L;
            return true;
        } catch (IOException e) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException ignored) {
                }
            }
            this.lastError = e.toString();
            return false;
        }
    }

    private void sendControlMessage(ByteBuffer payload) {
        if (channel == null || targetAddress == null) {
            return;
        }
        try {
            payload.rewind();
            channel.send(payload, targetAddress);
        } catch (PortUnreachableException unreachable) {
            clearPublisherState();
        } catch (SocketException socketEx) {
            if (isConnectionReset(socketEx)) {
                clearPublisherState();
            }
        } catch (IOException ignored) {
            // A dropped renewal is retried on the next 1 s interval.
        }
    }

    private void clearPublisherState() {
        connected = false;
        latest = null;
    }

    private void failChannel(int port, long now, IOException failure) {
        String message = failure.toString();
        close();
        currentPort = port;
        lastError = message;
        nextOpenNanos = now + RETRY_OPEN_INTERVAL_NS;
    }

    private static boolean isConnectionReset(SocketException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("reset") || lower.contains("unreachable");
    }
}
