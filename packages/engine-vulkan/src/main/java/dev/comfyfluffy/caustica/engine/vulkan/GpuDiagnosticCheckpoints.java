package dev.comfyfluffy.caustica.engine.vulkan;

import dev.comfyfluffy.caustica.spi.vulkan.DebugMarkers;
import org.lwjgl.vulkan.NVDeviceDiagnosticCheckpoints;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.LongConsumer;
import java.util.ArrayList;
import java.util.List;

/** Bounded recording history for opaque NVIDIA checkpoint tokens; tokens are never memory addresses. */
public final class GpuDiagnosticCheckpoints {
    public static final boolean ENABLED = Boolean.getBoolean("caustica.debug.gpu-checkpoints");
    private static final Ring HISTORY = ENABLED ? new Ring(16_384) : null;

    private GpuDiagnosticCheckpoints() { }

    /** Called only when checkpoint recording is enabled; unsupported devices preserve the host scope. */
    public static DebugMarkers.Scope begin(VkCommandBuffer command, String label, DebugMarkers.Scope scope) {
        if (command.getCapabilities().vkCmdSetCheckpointNV == 0L) return scope;
        return wrap(HISTORY, command.address(), label,
                token -> NVDeviceDiagnosticCheckpoints.vkCmdSetCheckpointNV(command, token), scope);
    }

    static DebugMarkers.Scope wrap(Ring history, long command, String label,
                                   LongConsumer record, DebugMarkers.Scope scope) {
        record.accept(history.add(command, label, Boundary.START));
        return () -> {
            try { record.accept(history.add(command, label, Boundary.END)); }
            finally { scope.close(); }
        };
    }

    public static Entry resolve(long token) {
        return HISTORY == null ? null : HISTORY.resolve(token);
    }

    /** Recording context only: neighboring tokens do not establish GPU execution progress. */
    public static List<Entry> neighborhood(long token) {
        return HISTORY == null ? List.of() : HISTORY.neighborhood(token);
    }

    public enum Boundary { START, END }

    public record Entry(long token, long command, String label, Boundary boundary) { }

    /** A replaced slot must not resolve an older token to a newer command's label. */
    static final class Ring {
        private final Entry[] entries;
        private long sequence;

        Ring(int capacity) { entries = new Entry[capacity]; }

        synchronized long add(long command, String label, Boundary boundary) {
            long token = ++sequence;
            entries[index(token)] = new Entry(token, command, label, boundary);
            return token;
        }

        synchronized Entry resolve(long token) {
            Entry entry = entries[index(token)];
            return entry != null && entry.token() == token ? entry : null;
        }

        synchronized List<Entry> neighborhood(long token) {
            Entry center = resolve(token);
            if (center == null) return List.of();
            var result = new ArrayList<Entry>();
            for (int offset = -24; offset <= 8; offset++) {
                Entry entry = resolve(token + offset);
                if (entry != null && entry.command() == center.command()) result.add(entry);
            }
            return result;
        }

        private int index(long token) { return (int) Long.remainderUnsigned(token, entries.length); }
    }
}
