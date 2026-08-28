package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.retained.RetainedId;

/**
 * A registered volume implementation selected by a mesh geometry's interior slot. The id belongs to the
 * context which issued it; geometry in that context may retain it, while a stale id resolves to vacuum.
 * Opaque — see {@link RetainedId}.
 */
public interface VolumeId extends RetainedId {
}
