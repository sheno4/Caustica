package dev.comfyfluffy.caustica.nvidia.nrd;

/** Failure reported by the native NRD integration boundary. */
public final class NrdException extends RuntimeException {
    public NrdException(String message) { super(message); }
    public NrdException(String message, Throwable cause) { super(message, cause); }
}
