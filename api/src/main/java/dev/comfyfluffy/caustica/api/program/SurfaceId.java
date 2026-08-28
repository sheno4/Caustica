package dev.comfyfluffy.caustica.api.program;

import dev.comfyfluffy.caustica.api.retained.RetainedId;

/**
 * A registered surface and coverage implementation selected directly by a mesh geometry. The id belongs
 * to the context which issued it; geometry in that context may retain it, while a stale id resolves to the
 * renderer's visible error surface. Opaque — see {@link RetainedId}.
 */
public interface SurfaceId extends RetainedId {
}
