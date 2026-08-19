package dev.comfyfluffy.caustica.api;

/** Resolves a feature-scoped option view without exposing the host's storage implementation. */
@FunctionalInterface
public interface OptionLookup {
    OptionValues options(ResourceId featureId);

    /**
     * Returns a lookup whose values remain fixed while a frame is recorded. Immutable lookups may return
     * themselves.
     */
    default OptionLookup snapshot() {
        return this;
    }
}
