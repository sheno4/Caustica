package dev.comfyfluffy.caustica.api.shader;

/** Typed descriptor-heap interface discovered for one entry point. */
public record ShaderHeapAbi(
        int version, int pushDataSize, boolean readsResourceHeap, boolean readsSamplerHeap
) {
    public static final int CURRENT_VERSION = 1;

    public ShaderHeapAbi {
        if (version != CURRENT_VERSION) throw new IllegalArgumentException("unsupported heap ABI version");
        if (pushDataSize < 0) throw new IllegalArgumentException("pushDataSize cannot be negative");
    }
}
