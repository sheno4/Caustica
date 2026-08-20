package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialVariant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable binding lookup and surface-compilation status for one material epoch. */
public final class MaterialEpochSnapshot implements MaterialSnapshot {
    private final long epoch;
    private final Map<ResourceId, int[]> ids;
    private final int[] fallbackVariants;
    private final Map<ResourceId, Integer> namedMaterialIds;
    private final Set<ResourceId> availableSurfaces;
    private final List<RtMaterialDesc> descriptions;
    private final RtMaterialRegistry.CompiledOverrideLookup overrides;
    private final int[] cutoutVariants;
    private final byte[] sbtClasses;

    MaterialEpochSnapshot(long epoch, Map<ResourceId, int[]> ids, int[] fallbackVariants,
                          Map<ResourceId, Integer> namedMaterialIds, Set<ResourceId> availableSurfaces,
                          List<RtMaterialDesc> descriptions,
                          RtMaterialRegistry.CompiledOverrideLookup overrides,
                          int[] cutoutVariants, byte[] sbtClasses) {
        this.epoch = epoch;
        this.ids = ids;
        this.fallbackVariants = fallbackVariants;
        this.namedMaterialIds = namedMaterialIds;
        this.availableSurfaces = Set.copyOf(availableSurfaces);
        this.descriptions = List.copyOf(descriptions);
        this.overrides = overrides;
        this.cutoutVariants = cutoutVariants;
        this.sbtClasses = sbtClasses;
    }

    @Override
    public long epoch() { return epoch; }

    @Override
    public boolean surfaceAvailable(ResourceId surface) {
        return availableSurfaces.contains(surface);
    }

    public int bindingId(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }

    public RtMaterialDesc material(int materialId) { return descriptions.get(materialId); }

    public int withCutoutCoverage(int materialId) { return cutoutVariants[materialId]; }
    public int sbtClassFor(int materialId) { return sbtClasses[materialId]; }

    public int resolve(ResourceId material, ResourceId geometry, MaterialVariant variant) {
        int index = MaterialRegistryCompiler.index(variant.profile(), variant.topology(), variant.emitting());
        int[] override = overrides.resolve(material, geometry);
        if (override != null) return override[index];
        int[] variants = ids.get(material);
        return variants != null ? variants[index] : fallbackVariants[index];
    }

}
