package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable binding lookup and surface-compilation status for one material epoch. */
public final class MaterialEpochSnapshot implements MaterialSnapshot {
    private final long epoch;
    private final Map<ResourceId, Integer> namedMaterialIds;
    private final Set<ResourceId> availableSurfaces;
    private final List<RtMaterialDesc> descriptions;
    private final byte[] sbtClasses;

    MaterialEpochSnapshot(long epoch, Map<ResourceId, Integer> namedMaterialIds,
                          Set<ResourceId> availableSurfaces, List<RtMaterialDesc> descriptions,
                          byte[] sbtClasses) {
        this.epoch = epoch;
        this.namedMaterialIds = namedMaterialIds;
        this.availableSurfaces = Set.copyOf(availableSurfaces);
        this.descriptions = List.copyOf(descriptions);
        this.sbtClasses = sbtClasses.clone();
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

    public int sbtClassFor(int materialId) { return sbtClasses[materialId]; }

}
