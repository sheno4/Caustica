package dev.comfyfluffy.caustica.api.light;

import dev.comfyfluffy.caustica.api.retained.RetainedId;

/**
 * A retained light, in one scene. Issued by {@link LightChannel#newLight()}. Opaque — see
 * {@link RetainedId}.
 */
public interface LightId extends RetainedId {
}
