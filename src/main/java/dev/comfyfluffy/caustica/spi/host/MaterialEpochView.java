package dev.comfyfluffy.caustica.spi.host;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;

/**
 * Immutable material lookup published to first-party host scene workers for one resource epoch.
 * Binding IDs are private to the publishing renderer epoch. This host integration SPI is not supported extension API.
 */
public interface MaterialEpochView {
    long epoch();

    float defaultUniformEmissionLuminanceCdM2();

    int emissionFootprintResolution();

    int bindingId(ResourceId material);

    int resolve(ResourceId material, ResourceId geometry, MaterialVariant variant);

    int withCutoutCoverage(int materialId);

    /** Non-allocating emission lookups used for every candidate terrain emitter. */
    EmissionSource emissionSource(int materialId);

    float emissionLuminanceCdM2(int materialId);

    EmissionFootprint emissionFootprint(int materialId);

    /** How a material's shaded emission is sourced. */
    enum EmissionSource {
        NONE,
        AUTHORED_MASK,
        DERIVED_MASK,
        GEOMETRY_UNIFORM
    }
}
