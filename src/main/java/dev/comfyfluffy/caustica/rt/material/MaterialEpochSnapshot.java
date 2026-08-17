package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;
import dev.comfyfluffy.caustica.spi.host.MaterialEpochView;

import java.util.List;
import java.util.Map;

/** Internal material snapshot retaining renderer metadata while implementing the narrow host material view. */
public final class MaterialEpochSnapshot implements MaterialEpochView {
    private final long epoch;
    private final Map<ResourceId, int[]> ids;
    private final int[] fallbackVariants;
    private final float defaultUniformEmissionLuminanceCdM2;
    private final int emissionFootprintResolution;
    private final Map<ResourceId, Integer> namedMaterialIds;
    private final List<RtMaterialDesc> descriptions;
    private final List<EmissionFootprint> footprints;
    private final RtMaterialRegistry.CompiledOverrideLookup overrides;
    private final int[] cutoutVariants;
    private final byte[] sbtClasses;

    MaterialEpochSnapshot(long epoch, Map<ResourceId, int[]> ids, int[] fallbackVariants,
                          float defaultUniformEmissionLuminanceCdM2, int emissionFootprintResolution,
                          Map<ResourceId, Integer> namedMaterialIds, List<RtMaterialDesc> descriptions,
                          List<EmissionFootprint> footprints,
                          RtMaterialRegistry.CompiledOverrideLookup overrides,
                          int[] cutoutVariants, byte[] sbtClasses) {
        this.epoch = epoch;
        this.ids = ids;
        this.fallbackVariants = fallbackVariants;
        this.defaultUniformEmissionLuminanceCdM2 = defaultUniformEmissionLuminanceCdM2;
        this.emissionFootprintResolution = emissionFootprintResolution;
        this.namedMaterialIds = namedMaterialIds;
        this.descriptions = descriptions;
        this.footprints = footprints;
        this.overrides = overrides;
        this.cutoutVariants = cutoutVariants;
        this.sbtClasses = sbtClasses;
    }

    @Override
    public long epoch() { return epoch; }

    @Override
    public float defaultUniformEmissionLuminanceCdM2() { return defaultUniformEmissionLuminanceCdM2; }

    @Override
    public int emissionFootprintResolution() { return emissionFootprintResolution; }

    @Override
    public int bindingId(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }

    public int materialCount() { return descriptions.size(); }
    public RtMaterialDesc material(int materialId) { return descriptions.get(materialId); }
    @Override
    public EmissionFootprint emissionFootprint(int materialId) { return footprints.get(materialId); }

    @Override
    public int withCutoutCoverage(int materialId) { return cutoutVariants[materialId]; }
    public int sbtClassFor(int materialId) { return sbtClasses[materialId]; }

    @Override
    public int resolve(ResourceId material, ResourceId geometry, MaterialVariant variant) {
        int index = MaterialRegistryCompiler.index(variant.profile(), variant.topology(), variant.emitting());
        int[] override = overrides.resolve(material, geometry);
        if (override != null) return override[index];
        int[] variants = ids.get(material);
        return variants != null ? variants[index] : fallbackVariants[index];
    }

    @Override
    public EmissionSource emissionSource(int materialId) {
        return switch (descriptions.get(materialId).emissionSource()) {
            case NONE -> EmissionSource.NONE;
            case AUTHORED_MASK -> EmissionSource.AUTHORED_MASK;
            case DERIVED_MASK -> EmissionSource.DERIVED_MASK;
            case GEOMETRY_UNIFORM -> EmissionSource.GEOMETRY_UNIFORM;
        };
    }

    @Override
    public float emissionLuminanceCdM2(int materialId) {
        return descriptions.get(materialId).emissionLuminance();
    }
}
