package dev.comfyfluffy.caustica.minecraft.api;

/** Monotonic host generation of the client resources visible to a world session. */
public record ResourcePackEpoch(long generation) {
    public ResourcePackEpoch {
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
    }
}
