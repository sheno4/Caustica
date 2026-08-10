package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.ResourceId;

/** Host adapter lookup for a source material's default dielectric index of refraction. */
@FunctionalInterface
public interface MaterialIorLookup {
    float ior(ResourceId material);
}
