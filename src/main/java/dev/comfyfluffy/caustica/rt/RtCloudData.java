package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Immutable, byte-packed Alpha-Piscium noise tables, shared by both ray generation variants. */
public final class RtCloudData {
    private static final String ROOT = "/caustica/clouds/";
    private static final int NOISE_BYTES = 6291456;
    private static final int CIRRUS_BYTES = 256 * 256;
    private static final int PHASE_BYTES = 256 * 3 * 8;
    private RtBuffer buffer;

    public long address(RtContext ctx) {
        if (buffer != null) return buffer.deviceAddress;
        RtBuffer created = ctx.createBuffer(NOISE_BYTES + CIRRUS_BYTES + PHASE_BYTES,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "Alpha-Piscium cloud tables");
        try {
            ByteBuffer target = MemoryUtil.memByteBuffer(created.mapped, (int) created.size);
            load(target, "cumulus_base.bin", 1048576);
            load(target, "cumulus_detail1.bin", 2097152);
            load(target, "cumulus_detail2.bin", 2097152);
            load(target, "cumulus_curl.bin", 1048576);
            try (InputStream stream = resource("cirrus_20.png")) {
                var image = ImageIO.read(stream);
                if (image == null || image.getWidth() != 256 || image.getHeight() != 256) {
                    throw new IOException("Invalid cirrus table dimensions");
                }
                for (int y = 0; y < 256; y++) {
                    for (int x = 0; x < 256; x++) {
                        target.put((byte) image.getRaster().getSample(x, y, 0));
                    }
                }
            }
            load(target, "opac_cloud_phases.bin", PHASE_BYTES);
            created.flush(0, created.size);
            buffer = created;
            return buffer.deviceAddress;
        } catch (IOException | RuntimeException | Error failure) {
            created.destroy();
            throw new IllegalStateException("Cannot load Alpha-Piscium cloud tables", failure);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream stream = RtCloudData.class.getResourceAsStream(ROOT + name);
        if (stream == null) throw new IOException("Missing cloud resource " + name);
        return stream;
    }

    private static void load(ByteBuffer target, String name, int size) throws IOException {
        try (InputStream stream = resource(name)) {
            byte[] data = stream.readNBytes(size + 1);
            if (data.length != size) throw new IOException("Invalid cloud resource size: " + name);
            target.put(data);
        }
    }

    /** Called only after the renderer's device-idle teardown barrier. */
    public void destroy() {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
    }
}
