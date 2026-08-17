package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialAnalysis;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;

import java.util.List;
import java.util.Map;

/** Internal compiled material snapshot with a binding-free semantic view for scene providers. */
public final class MaterialEpochSnapshot implements MaterialSnapshot {
    private final long epoch;
    private final Map<ResourceId, int[]> ids;
    private final int[] fallbackVariants;
    private final Map<ResourceId, Integer> namedMaterialIds;
    private final Map<ResourceId, Integer> runtimeTextureIds;
    private final int runtimeFallbackId;
    private final List<RtMaterialDesc> descriptions;
    private final List<EmissionFootprint> footprints;
    private final MaterialAnalysis[] analyses;
    private final RtMaterialRegistry.CompiledOverrideLookup overrides;
    private final int[] cutoutVariants;
    private final byte[] sbtClasses;

    MaterialEpochSnapshot(long epoch, Map<ResourceId, int[]> ids, int[] fallbackVariants,
                          int emissionFootprintResolution,
                          Map<ResourceId, Integer> namedMaterialIds, Map<ResourceId, Integer> runtimeTextureIds,
                          int runtimeFallbackId, List<RtMaterialDesc> descriptions,
                          List<EmissionFootprint> footprints,
                          RtMaterialRegistry.CompiledOverrideLookup overrides,
                          int[] cutoutVariants, byte[] sbtClasses) {
        this.epoch = epoch;
        this.ids = ids;
        this.fallbackVariants = fallbackVariants;
        this.namedMaterialIds = namedMaterialIds;
        this.runtimeTextureIds = runtimeTextureIds;
        this.runtimeFallbackId = runtimeFallbackId;
        this.descriptions = descriptions;
        this.footprints = footprints;
        this.overrides = overrides;
        this.cutoutVariants = cutoutVariants;
        this.sbtClasses = sbtClasses;
        this.analyses = new MaterialAnalysis[descriptions.size()];
        for (int i = 0; i < analyses.length; i++) {
            RtMaterialDesc description = descriptions.get(i);
            analyses[i] = new MaterialAnalysis(switch (description.emissionSource()) {
                case NONE -> MaterialAnalysis.EmissionSource.NONE;
                case AUTHORED_MASK -> MaterialAnalysis.EmissionSource.AUTHORED_MASK;
                case DERIVED_MASK -> MaterialAnalysis.EmissionSource.DERIVED_MASK;
                case GEOMETRY_UNIFORM -> MaterialAnalysis.EmissionSource.GEOMETRY_UNIFORM;
            }, description.emissionLuminance(), footprints.get(i), emissionFootprintResolution);
        }
    }

    @Override
    public long epoch() { return epoch; }

    public int bindingId(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }

    public int materialCount() { return descriptions.size(); }
    public RtMaterialDesc material(int materialId) { return descriptions.get(materialId); }
    public EmissionFootprint emissionFootprint(int materialId) { return footprints.get(materialId); }

    public int withCutoutCoverage(int materialId) { return cutoutVariants[materialId]; }
    public int sbtClassFor(int materialId) { return sbtClasses[materialId]; }

    public int resolve(ResourceId material, ResourceId geometry, MaterialVariant variant) {
        int index = MaterialRegistryCompiler.index(variant.profile(), variant.topology(), variant.emitting());
        int[] override = overrides.resolve(material, geometry);
        if (override != null) return override[index];
        int[] variants = ids.get(material);
        return variants != null ? variants[index] : fallbackVariants[index];
    }

    @Override
    public MaterialAnalysis analyze(SceneMesh.MaterialReference material) {
        int id = switch (material) {
            case SceneMesh.NamedMaterial named -> requireNamed(named.material().id());
            case SceneMesh.CatalogMaterial catalog -> resolve(
                    catalog.material(), catalog.geometry(), catalog.variant());
            case SceneMesh.AtlasMaterial atlas -> runtimeTextureIds.getOrDefault(
                    atlas.reference().material(), runtimeFallbackId);
            case SceneMesh.StandaloneMaterial standalone -> runtimeTextureIds.getOrDefault(
                    standalone.material(), runtimeFallbackId);
            case SceneMesh.FallbackMaterial ignored -> runtimeFallbackId;
        };
        return analyses[id];
    }

    private int requireNamed(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }
}
