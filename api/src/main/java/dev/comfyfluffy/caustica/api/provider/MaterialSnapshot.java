package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.ResourceId;

/**
 * Immutable material-compilation status for one resource epoch. Scene workers may retain this snapshot
 * until their work finishes; renderer binding and texture indices are intentionally absent.
 */
public interface MaterialSnapshot {
    long epoch();

    /**
     * Whether a named surface implementation compiled and is usable in this epoch's world program.
     * Returns false for an unregistered name, the explicit error surface, or a probe-rejected implementation.
     */
    boolean surfaceAvailable(ResourceId surface);
}
