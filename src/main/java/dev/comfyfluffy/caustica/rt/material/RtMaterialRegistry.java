package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.engine.color.ColorTransforms;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.gen.MaterialBindingData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData.Float4;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resource-epoch material registry shared by all geometry producers.
 *
 * <p>The compiled record is two tables. {@link SurfaceMaterialData} carries the closest-hit surface
 * parameters; {@link MaterialBindingData} carries the sixteen bytes traversal reads — coverage mode,
 * bindless base-color texture index, and the uniform shadow transmittance — and names a surface. Geometry
 * stores binding IDs, so pairing one surface with another base-color texture or coverage mode is a
 * sixteen-byte append
 * rather than a cloned material, and bindings are interned on content: the same triple is one ID however
 * it was reached.
 *
 * <p>Workers read an immutable {@link MaterialEpochSnapshot}. Live binding interning is render-thread-only;
 * shared-atlas surfaces append into pre-reserved table slots because their stitched UV rectangles only
 * become available during capture. Published records never mutate.
 */
public final class RtMaterialRegistry {

    // Canonical transport/feature values compiled into SurfaceMaterial and MaterialBinding.
    // RtMaterialPageCompiler.Entry.features uses the same bit values, so entry features flow into surfaces
    // with a plain mask.
    public static final int TRANSPORT_SURFACE = 0;
    public static final int TRANSPORT_MEDIUM_BOUNDARY = 3;
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    /** A per-texel emission mask was compiled into the page; it says nothing about where it came from. */
    public static final int FEATURE_EMISSION_MASK = 4;
    /** The source binds canonical {@code subsurface_color} to evaluated {@code base_color}. */
    public static final int FEATURE_SUBSURFACE_COLOR_BASE = 8;
    /** The CPU compiler derived the canonical emission RGB page from source {@code base_color}. */
    public static final int FEATURE_EMISSION_COLOR_BASE = 16;
    /** A named definition emits uniformly without requiring per-primitive source state. */
    public static final int FEATURE_UNIFORM_EMISSION = 32;
    /** Bindless base-color texture index reserved for the host's shared atlas. */
    public static final int SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX = 0;
    /**
     * The surface implementation every material compiles with unless an override names another.
     * {@code caustica:builtin} registers its own first, so index 0 is always the reference surface.
     */
    public static final int BUILTIN_SURFACE_IMPLEMENTATION = 0;

    // Coverage — is the surface present along this ray — mirrored by world_common.slang's COVERAGE_*.
    private static final int COVERAGE_OPAQUE = MaterialBindingAbi.COVERAGE_OPAQUE;
    private static final int COVERAGE_CUTOUT = MaterialBindingAbi.COVERAGE_CUTOUT;
    private static final int COVERAGE_STOCHASTIC = MaterialBindingAbi.COVERAGE_STOCHASTIC;
    // Transmittance — how much light passes where the surface is present. Mirrors BINDING_* in Slang.
    private static final int BINDING_TRANSMISSIVE = MaterialBindingAbi.FLAG_TRANSMISSIVE;
    // MaterialBinding.packed0 = baseColorTextureIndex:16 | coverageMode:2 | flags:6 | surfaceImpl:8;
    // packed1 = coverageCutoff:8. Mirrored by the bindingBaseColorTextureIndex/bindingCoverage/bindingFlags/
    // bindingSurfaceImpl/bindingCutoff accessors in world_common.slang — the any-hit reads these fields
    // out of one aligned load, so the shifts are ABI.

    // Coverage cutoffs, packed into MaterialBinding.packed1 so no any-hit branches on the producer.
    private static final float PRIMARY_COVERAGE_CUTOFF = 0.5f;
    private static final float RUNTIME_TEXTURE_COVERAGE_CUTOFF = 0.1f;
    // Faint baseline extinction added to every translucent surface regardless of its colour, so perfectly
    // clear glass/ice still dims a shadow ray a little instead of being invisible to it (real glass isn't
    // a perfect transmitter either). Additive with the colour-derived extinction, not a floor — a tinted
    // pane still absorbs its own colour on top of this.
    private static final float TRANSLUCENT_NEUTRAL_EXTINCTION = 0.15f;
    private static final int WHITE_SHADOW_TINT = 0x00FFFFFF;
    // HDR radiance of a full (level-15-equivalent) emitter, modulated by albedo. Baked into every
    // emissive RtMaterialDesc.emissionLuminance at compile time, times
    // any resource-pack absolute emission.luminance_cd_m2 override; see surface() and RtMaterialOverrides.
    //
    // Photometric: cd/m² of the emitting surface, per {@link dev.comfyfluffy.caustica.rt.RtSceneUnits}.
    //
    // Anchored on luminous exitance: a full-strength emitter radiates about 1,000 lm/m².
    // Lambertian exitance M = π·L, so L = 1000/π = 318 cd/m².
    private static final int EMISSION_LUMINANCE_SHIFT = 8;
    private static final int EMISSION_LUMINANCE_MASK = 65535;
    // Ceiling of the 16-bit fixed-point luminance field, raised with the baseline above. HALF_MAX is the
    // real transport ceiling downstream — Payload.emissionSss is a half2 lane and Light.le is packed
    // R11G11B10 — so clamping here rather than higher keeps the encoded value representable end to end.
    // The quantisation step is MAX/65535 ≈ 1 cd/m², i.e. 0.007% at the baseline. A resource pack's
    // maximum 5x multiplier would reach 75,000 and clamps to this: a 0.19 EV reduction on something
    // already several EV past display white, so invisible.
    private static final float MAX_EMISSION_LUMINANCE = 65504.0f;
    // SurfaceMaterial.page = pageIndex:24 | maxLod:8. The LOD limit describes the page rectangle rather
    // than the surface, so it travels with the page reference; mirrored by surfacePage/surfacePageMaxLod.
    private static final int MAX_LOD_SHIFT = 24;
    private static final int PAGE_MASK = 0xFFFFFF;

    private static final int TOPOLOGY_VARIANTS = 2;
    private static final int EMISSION_VARIANTS = 2; // state-gated emission disabled/enabled
    private static final int VARIANT_SURFACE = 0;
    private static final int VARIANT_MEDIUM_BOUNDARY = 1;
    // Source adapters map geometry onto this finite set before calling the registry.
    private static final OpenPbrMaterialProfile[] TEXTURE_PROFILES = MaterialRegistryCompiler.TEXTURE_PROFILES;

    private volatile MaterialEpochSnapshot snapshot;
    private GpuBuffer bindingTable;
    private GpuBuffer surfaceTable;
    private long nextEpoch;
    private Map<ResourceId, Integer> runtimeTextureIds = Map.of();
    private Map<ResourceId, Integer> namedMaterialIds = Map.of();
    private Map<ResourceId, RuntimeTemplate> runtimeTemplates = Map.of();
    private final Map<AtlasMaterialReference, Integer> atlasReferenceIds = new HashMap<>();
    // Host mirror of the uploaded binding table, and its content index. Every binding — compiled or
    // appended — is interned here, so deriving a variant twice by different routes yields one ID.
    private final List<MaterialBindingData> bindingRecords = new ArrayList<>();
    private final List<SurfaceMaterialData> surfaceRecords = new ArrayList<>();
    private final Map<MaterialBindingData, Integer> bindingIds = new HashMap<>();
    private int runtimeFallbackId;
    private int bindingCapacity;
    private int nextSurfaceId;
    private int surfaceCapacity;

    private record RuntimeTemplate(RtMaterialDesc desc, RtMaterialPageCompiler.Entry entry) {
    }

    RtMaterialRegistry() {
    }

    /** Build and atomically publish the registry for the current resource epoch. */
    public void rebuild(GpuContext ctx, RtMaterialPageCompiler pageCompiler, MaterialCatalog catalog,
                        RtMaterialOverrides overrides, List<MaterialDefinition> definitions,
                        RtMaterialOverrides.SurfaceResolver surfaces, int runtimeTextureCapacity) {
        Map<ResourceId, RtMaterialPageCompiler.Entry> entries = pageCompiler.preparedEntries();
        float fallbackEmissionLuminance = 1.0f;
        List<ResourceId> atlasAssets = catalog.atlasAssets().stream()
                .map(MaterialTextureAsset::material).toList();
        List<ResourceId> standaloneAssets = catalog.standalone().stream()
                .map(MaterialTextureAsset::material).toList();
        Map<ResourceId, MaterialTextureAsset> assets = new HashMap<>();
        catalog.atlasAssets().forEach(asset -> assets.put(asset.material(), asset));
        catalog.standalone().forEach(asset -> assets.put(asset.material(), asset));
        Map<ResourceId, MaterialDefinition> definitionsById = new HashMap<>();
        definitions.forEach(definition -> definitionsById.put(definition.id(), definition));
        RtMaterialPageCompiler.Entry fallbackEntry = pageCompiler.entry(null);

        int profileVariants = MaterialRegistryCompiler.variantCount();
        CompiledTables tables = new CompiledTables(2 + profileVariants + atlasAssets.size() * profileVariants);
        tables.add(compileDesc(TRANSPORT_SURFACE, 0, OpenPbrMaterialProfile.ROUGH_DIELECTRIC, false, true,
                RtMaterialDesc.EmissionSummary.NONE, fallbackEmissionLuminance), transparentWhiteAverage(), fallbackEntry, null,
                PRIMARY_COVERAGE_CUTOFF, true);
        int[] fallbackVariants = new int[profileVariants];
        for (OpenPbrMaterialProfile profile : TEXTURE_PROFILES) {
            for (MaterialTopology topology : MaterialTopology.values()) {
                for (boolean emitting : new boolean[]{false, true}) {
                    int variant = index(profile, topology, emitting);
                    if (profile.equals(OpenPbrMaterialProfile.ROUGH_DIELECTRIC)
                            && topology == MaterialTopology.SURFACE && !emitting) {
                        fallbackVariants[variant] = 0;
                        continue;
                    }
                    fallbackVariants[variant] = tables.add(
                            compileDesc(transport(topology), 0, profile, emitting, true,
                                    RtMaterialDesc.EmissionSummary.NONE, fallbackEmissionLuminance),
                            transparentWhiteAverage(), fallbackEntry, null, PRIMARY_COVERAGE_CUTOFF,
                            topology == MaterialTopology.SURFACE);
                }
            }
        }
        int nextRuntimeFallbackId = tables.add(
                compileRuntimeTextureDesc(0, true, RtMaterialDesc.EmissionSummary.NONE,
                        fallbackEmissionLuminance),
                transparentWhiteAverage(), fallbackEntry, null, RUNTIME_TEXTURE_COVERAGE_CUTOFF);
        Map<ResourceId, int[]> ids = new HashMap<>();
        List<MutableCompiledOverride> compiledOverrides = new ArrayList<>();
        Map<ResourceId, List<MutableCompiledOverride>> compiledOverridesByMaterial = new HashMap<>();
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            MutableCompiledOverride compiled = new MutableCompiledOverride(rule);
            compiledOverrides.add(compiled);
            compiledOverridesByMaterial.computeIfAbsent(rule.material(), ignored -> new ArrayList<>()).add(compiled);
        }
        for (ResourceId material : atlasAssets) {
            RtMaterialPageCompiler.Entry entry = entries.get(material);
            int baseFeatures = entry.features()
                    & (FEATURE_SPEC | FEATURE_NORMAL | FEATURE_EMISSION_MASK
                    | FEATURE_SUBSURFACE_COLOR_BASE | FEATURE_EMISSION_COLOR_BASE);
            List<MutableCompiledOverride> materialOverrides = compiledOverridesByMaterial.getOrDefault(
                    material, List.of());

            // The first material-wide rule owns every geometry use of this material.
            MutableCompiledOverride materialWide = null;
            for (MutableCompiledOverride compiled : materialOverrides) {
                if (compiled.rule.geometry() == null) {
                    materialWide = compiled;
                    compiled.matchedMaterial = true;
                    break;
                }
            }
            MaterialTextureAsset asset = assets.get(material);
            float dielectricIor = asset.dielectricIor();
            float uniformEmissionLuminance = asset.uniformEmissionLuminanceCdM2();
            int[] variants = new int[profileVariants];
            for (OpenPbrMaterialProfile profile : TEXTURE_PROFILES) {
                for (MaterialTopology topology : MaterialTopology.values()) {
                    for (boolean emitting : new boolean[]{false, true}) {
                        int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_EMISSION_MASK;
                        RtMaterialDesc desc = compileDesc(transport(topology), features,
                                profile, emitting, false,
                                variantSummary(features, emitting, entry, entry.uniformEmissionSummary()),
                                dielectricIor, uniformEmissionLuminance);
                        if (materialWide != null) {
                            desc = materialWide.rule.apply(desc);
                        }
                        variants[index(profile, topology, emitting)] = tables.add(desc, entry.average(),
                                entry, entry.uniformEmissionFootprint(), PRIMARY_COVERAGE_CUTOFF,
                                topology == MaterialTopology.SURFACE);
                    }
                }
            }
            ids.put(material, variants);

            for (MutableCompiledOverride compiled : materialOverrides) {
                if (compiled.rule.geometry() == null) continue;
                int[] overrideVariants = new int[profileVariants];
                for (OpenPbrMaterialProfile profile : TEXTURE_PROFILES) {
                    for (MaterialTopology topology : MaterialTopology.values()) {
                        for (boolean emitting : new boolean[]{false, true}) {
                            int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_EMISSION_MASK;
                            RtMaterialDesc base = compileDesc(transport(topology),
                                    features, profile, emitting, false,
                                    variantSummary(features, emitting, entry, entry.uniformEmissionSummary()),
                                    dielectricIor, uniformEmissionLuminance);
                            RtMaterialDesc desc = compiled.rule.apply(base);
                            overrideVariants[index(profile, topology, emitting)] = tables.add(desc,
                                    entry.average(), entry, entry.uniformEmissionFootprint(), PRIMARY_COVERAGE_CUTOFF,
                                    topology == MaterialTopology.SURFACE);
                        }
                    }
                }
                compiled.ids.put(material, overrideVariants);
            }
        }

        Map<ResourceId, Integer> nextRuntimeTextureIds = new HashMap<>();
        Map<ResourceId, Integer> nextNamedMaterialIds = new HashMap<>();
        Map<ResourceId, RuntimeTemplate> nextRuntimeTemplates = new HashMap<>();
        Set<RtMaterialOverrides.Rule> runtimeMatchedOverrides = new HashSet<>();
        for (ResourceId material : standaloneAssets) {
            if (definitionsById.containsKey(material)) continue;
            MaterialTextureAsset asset = assets.get(material);
            RtMaterialPageCompiler.Entry entry = entries.get(material);
            int features = entry.features() & (FEATURE_SPEC | FEATURE_NORMAL | FEATURE_EMISSION_MASK
                    | FEATURE_SUBSURFACE_COLOR_BASE | FEATURE_EMISSION_COLOR_BASE);
            RtMaterialDesc desc = compileRuntimeTextureDesc(features, false, entry.emissionSummary(),
                    asset.uniformEmissionLuminanceCdM2());
            for (MutableCompiledOverride compiled : compiledOverridesByMaterial.getOrDefault(material, List.of())) {
                RtMaterialOverrides.Rule rule = compiled.rule;
                if (rule.geometry() != null) continue;
                desc = rule.apply(desc);
                runtimeMatchedOverrides.add(rule);
                break;
            }
            int id = tables.add(desc, transparentWhiteAverage(), entry, null,
                    RUNTIME_TEXTURE_COVERAGE_CUTOFF);
            nextRuntimeTextureIds.put(material, id);
            nextNamedMaterialIds.put(material, id);
            nextRuntimeTemplates.put(material, new RuntimeTemplate(desc, entry));
        }

        for (MaterialDefinition definition : definitions) {
            if (nextNamedMaterialIds.containsKey(definition.id())) {
                throw new IllegalStateException("Duplicate submitted material " + definition.id());
            }
            int surfaceImplementation = BUILTIN_SURFACE_IMPLEMENTATION;
            if (definition.surface() != null) {
                surfaceImplementation = surfaces.indexOf(definition.surface());
                if (surfaceImplementation < 0) {
                    CausticaMod.LOGGER.warn("Ignoring material definition {} with unregistered surface {}",
                            definition.id(), definition.surface());
                    continue;
                }
            }
            int definitionTransport = transport(definition.topology());
            RtMaterialPageCompiler.Entry definitionEntry = entries.getOrDefault(definition.id(), fallbackEntry);
            int definitionFeatures = definitionFeatures(definition.textures() != null,
                    definitionEntry.features(), definition.emissionLuminanceCdM2());
            RtMaterialDesc.EmissionSource emissionSource = definition.emissionLuminanceCdM2() <= 0.0f
                    ? RtMaterialDesc.EmissionSource.NONE
                    : (definitionFeatures & FEATURE_EMISSION_MASK) != 0
                    ? RtMaterialDesc.EmissionSource.AUTHORED_MASK : RtMaterialDesc.EmissionSource.GEOMETRY_UNIFORM;
            RtMaterialDesc desc = new RtMaterialDesc(definitionTransport, RtMaterialDesc.Source.NEUTRAL,
                    definitionFeatures,
                    definition.specularRoughness(), definition.baseMetalness(), definition.specularIor(),
                    definition.transmissionWeight(), definition.transmissionColorR(), definition.transmissionColorG(),
                    definition.transmissionColorB(), definition.subsurfaceWeight(), definition.subsurfaceColorR(),
                    definition.subsurfaceColorG(), definition.subsurfaceColorB(),
                    definition.subsurfaceScatterAnisotropy(), definition.emissionColorR(),
                    definition.emissionColorG(), definition.emissionColorB(), emissionSource,
                    definition.emissionLuminanceCdM2(), definitionEntry.emissionSummary(), surfaceImplementation);
            for (MutableCompiledOverride compiled : compiledOverridesByMaterial.getOrDefault(
                    definition.id(), List.of())) {
                RtMaterialOverrides.Rule rule = compiled.rule;
                if (rule.geometry() != null) continue;
                desc = rule.apply(desc);
                runtimeMatchedOverrides.add(rule);
                break;
            }
            int id = tables.addDefinition(desc, definition, definitionEntry);
            nextNamedMaterialIds.put(definition.id(), id);
        }

        // Standalone textures have fixed UVs. Shared-atlas references append one surface with the
        // stitched UV rectangle supplied by the host at capture time.
        int surfaceCount = tables.surfaces.size();
        int nextSurfaceCapacity = Math.addExact(surfaceCount, Math.max(64, atlasAssets.size()));
        // Bindings are appended per surface, base-color texture index, and coverage mode actually submitted.
        int bindingCount = tables.bindings.size();
        int nextBindingCapacity = Math.addExact(bindingCount, Math.max(1024,
                Math.addExact(Math.addExact(atlasAssets.size(), Math.multiplyExact(standaloneAssets.size(), 3)),
                        Math.multiplyExact(runtimeTextureCapacity, 2))));
        GpuBuffer nextSurfaceTable = createTable(ctx, nextSurfaceCapacity, SurfaceMaterialData.BYTE_SIZE,
                "surface material table");
        GpuBuffer nextBindingTable = null;
        try {
            writeRecords(nextSurfaceTable, tables.surfaces, SurfaceMaterialData.BYTE_SIZE,
                    SurfaceMaterialData::write);
            nextBindingTable = createTable(ctx, nextBindingCapacity, MaterialBindingData.BYTE_SIZE,
                    "material binding table");
            writeRecords(nextBindingTable, tables.bindings, MaterialBindingData.BYTE_SIZE,
                    MaterialBindingData::write);
        } catch (Throwable t) {
            if (nextBindingTable != null) nextBindingTable.destroy();
            nextSurfaceTable.destroy();
            throw t;
        }

        GpuBuffer oldBindingTable = bindingTable;
        GpuBuffer oldSurfaceTable = surfaceTable;
        long epoch = ++nextEpoch;
        List<RtMaterialDesc> descriptions = tables.descriptions;
        List<EmissionFootprint> footprints = tables.footprints;
        List<CompiledOverrideLookup.Entry> frozenOverrides = compiledOverrides.stream()
                .filter(value -> !value.ids.isEmpty())
                .map(MutableCompiledOverride::freeze).toList();
        long matchedOverrideRules = 0;
        for (MutableCompiledOverride compiled : compiledOverrides) {
            if (!compiled.ids.isEmpty() || compiled.matchedMaterial
                    || runtimeMatchedOverrides.contains(compiled.rule)) {
                matchedOverrideRules++;
            } else {
                CausticaMod.LOGGER.warn("RT material override {} matched no compiled texture ({})",
                        compiled.rule.source(), compiled.rule.material());
            }
        }
        byte[] sbtClasses = new byte[tables.bindings.size()];
        for (int i = 0; i < sbtClasses.length; i++) {
            sbtClasses[i] = (byte) sbtClassOf(tables.bindings.get(i));
        }
        MaterialEpochSnapshot next = new MaterialEpochSnapshot(epoch, Collections.unmodifiableMap(ids), fallbackVariants,
                RtMaterialPageCompiler.EMISSION_FOOTPRINT_RESOLUTION,
                Collections.unmodifiableMap(new HashMap<>(nextNamedMaterialIds)),
                Collections.unmodifiableMap(new HashMap<>(nextRuntimeTextureIds)), nextRuntimeFallbackId,
                List.copyOf(descriptions), Collections.unmodifiableList(new ArrayList<>(footprints)),
                CompiledOverrideLookup.of(frozenOverrides),
                tables.cutoutVariants.toIntArray(), sbtClasses);
        runtimeTextureIds = Collections.unmodifiableMap(nextRuntimeTextureIds);
        namedMaterialIds = Collections.unmodifiableMap(nextNamedMaterialIds);
        runtimeTemplates = Collections.unmodifiableMap(nextRuntimeTemplates);
        atlasReferenceIds.clear();
        bindingRecords.clear();
        bindingRecords.addAll(tables.bindings);
        surfaceRecords.clear();
        surfaceRecords.addAll(tables.surfaces);
        bindingIds.clear();
        for (int i = 0; i < bindingRecords.size(); i++) {
            bindingIds.put(bindingRecords.get(i), i);
        }
        runtimeFallbackId = nextRuntimeFallbackId;
        bindingCapacity = nextBindingCapacity;
        nextSurfaceId = surfaceCount;
        surfaceCapacity = nextSurfaceCapacity;
        bindingTable = nextBindingTable;
        surfaceTable = nextSurfaceTable;
        snapshot = next; // volatile publication: map and arrays are never mutated afterward
        if (oldBindingTable != null) oldBindingTable.destroy();
        if (oldSurfaceTable != null) oldSurfaceTable.destroy();
        long emissive = descriptions.stream().filter(desc -> desc.emissionSource() != RtMaterialDesc.EmissionSource.NONE)
                .count();
        long inferred = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.DERIVED_MASK).count();
        long authoredEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.AUTHORED_MASK).count();
        long geometryUniformEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.GEOMETRY_UNIFORM).count();
        double averageCoverage = descriptions.stream().filter(desc -> desc.emissionSummary().emissive())
                .mapToDouble(desc -> desc.emissionSummary().coverage()).average().orElse(0.0);
        CausticaMod.LOGGER.info("RT materials: epoch={}, surfaces={}/{}, bindings={}/{}, atlasAssets={}, standaloneAssets={}, overrideRules={}, matchedOverrides={}, emissive={}, authoredMasks={}, derivedMasks={}, geometryUniformEmission={}, avgEmissionCoverage={}, tableKiB={}",
                epoch, surfaceCount, nextSurfaceCapacity, bindingCount, nextBindingCapacity,
                atlasAssets.size(), standaloneAssets.size(), overrides.rules().size(),
                matchedOverrideRules, emissive,
                authoredEmission, inferred, geometryUniformEmission,
                String.format(java.util.Locale.ROOT, "%.3f", averageCoverage),
                ((long) nextSurfaceCapacity * SurfaceMaterialData.BYTE_SIZE
                        + (long) nextBindingCapacity * MaterialBindingData.BYTE_SIZE) / 1024);
    }

    private static GpuBuffer createTable(GpuContext ctx, int capacity, int stride, String label) {
        long byteSize = Math.multiplyExact((long) capacity, stride);
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("RT " + label + " exceeds mapped-buffer limit: " + byteSize);
        }
        return ctx.createAsyncBuffer(byteSize, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, label);
    }

    private static <T> void writeRecords(GpuBuffer table, List<T> records, int stride,
                                         java.util.function.BiConsumer<T, ByteBuffer> writer) {
        ByteBuffer mapped = MemoryUtil.memByteBuffer(table.mapped(), records.size() * stride)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < records.size(); i++) {
            writer.accept(records.get(i), mapped.slice(i * stride, stride).order(ByteOrder.nativeOrder()));
        }
        table.flush();
    }

    public MaterialEpochSnapshot requireSnapshot() {
        MaterialEpochSnapshot current = snapshot;
        if (current == null) throw new IllegalStateException("RT materials are not prepared");
        return current;
    }

    /** Resolve a stable extension material name to the current resource epoch's private binding ID. */
    public int bindingId(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }

    /** Address of the {@code MaterialBinding} table, indexed by the material ID geometry records carry. */
    public long bindingTableAddress() {
        GpuBuffer current = bindingTable;
        if (current == null) throw new IllegalStateException("RT material binding table is not uploaded");
        return current.deviceAddress();
    }

    /** Address of the {@code SurfaceMaterial} table, indexed by {@code MaterialBinding.surface}. */
    public long surfaceTableAddress() {
        GpuBuffer current = surfaceTable;
        if (current == null) throw new IllegalStateException("RT surface material table is not uploaded");
        return current.deviceAddress();
    }

    /** Neutral canonical material used by runtime-only textures. */
    public int runtimeFallbackId() {
        return runtimeFallbackId;
    }

    public int runtimeFallbackId(boolean stochasticCoverage) {
        return stochasticCoverage ? withStochasticCoverage(runtimeFallbackId) : runtimeFallbackId;
    }

    /**
     * The binding resolving {@code bindingId}'s surface with stochastic coverage. A blended submission
     * decides presence with white noise whatever cutoff the material compiled, so this overrides the
     * coverage axis and leaves everything else — surface, base-color texture, transmittance — alone.
     */
    public int withStochasticCoverage(int bindingId) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(new MaterialBindingData(
                MaterialBindingAbi.pack(MaterialBindingAbi.baseColorTextureIndex(base.packed0()), COVERAGE_STOCHASTIC,
                        MaterialBindingAbi.flags(base.packed0()), MaterialBindingAbi.surfaceImplementation(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1()));
    }

    /**
     * The binding resolving {@code bindingId}'s surface with deterministic cutout coverage. Compiled
     * materials default to {@code COVERAGE_OPAQUE} (see {@link #binding}); a producer that knows its
     * footprint is genuinely masked calls this
     * to get the alpha-tested variant instead. Cheap: coverage lives in the separately-interned
     * {@code MaterialBindingData}, so this never touches the precompiled surface/profile-variant table.
     */
    public int withCutoutCoverage(int bindingId) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(new MaterialBindingData(
                MaterialBindingAbi.pack(MaterialBindingAbi.baseColorTextureIndex(base.packed0()), COVERAGE_CUTOUT,
                        MaterialBindingAbi.flags(base.packed0()), MaterialBindingAbi.surfaceImplementation(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1()));
    }

    /**
     * The SBT hit-group class {@code bindingId} needs: {@link RtAccel#CLASS_MASKED} when the surface's
     * footprint is genuinely tested (cutout/stochastic coverage, any-hit on both ray types),
     * {@link RtAccel#CLASS_TRANSMISSIVE} when it always covers its footprint but tints/passes light
     * (shadow any-hit only), else {@link RtAccel#CLASS_OPAQUE} (no any-hit at all). The one derivation
     * every geometry producer routes through.
     */
    public int sbtClassFor(int bindingId) {
        return sbtClassOf(bindingRecords.get(bindingId));
    }

    /** Whether the binding's sampled alpha is represented by the current canonical temporal-range pages. */
    public boolean opacityMicromapEligible(int bindingId) {
        MaterialBindingData binding = bindingRecords.get(bindingId);
        int flags = MaterialBindingAbi.flags(binding.packed0());
        return MaterialBindingAbi.coverage(binding.packed0()) == COVERAGE_CUTOUT
                && MaterialBindingAbi.baseColorTextureIndex(binding.packed0()) == SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX
                && (flags & (BINDING_TRANSMISSIVE | MaterialBindingAbi.FLAG_TEXTURELESS)) == 0
                && (surfaceRecords.get(binding.surface()).alphaFlags() & 3) != 0;
    }

    private static int sbtClassOf(MaterialBindingData binding) {
        if (MaterialBindingAbi.coverage(binding.packed0()) != COVERAGE_OPAQUE) return RtAccel.CLASS_MASKED;
        int flags = MaterialBindingAbi.flags(binding.packed0());
        return (flags & BINDING_TRANSMISSIVE) != 0 ? RtAccel.CLASS_TRANSMISSIVE : RtAccel.CLASS_OPAQUE;
    }

    /**
     * The binding pairing {@code bindingId}'s surface with a bindless base-color texture. Supplying a
     * texture is sampled in linear space and modulated by the named definition's uniform base color.
     */
    public int withBaseColorTextureIndex(int bindingId, int baseColorTextureIndex) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(baseColorTextureBinding(base, baseColorTextureIndex));
    }

    static MaterialBindingData baseColorTextureBinding(MaterialBindingData base, int baseColorTextureIndex) {
        int flags = (MaterialBindingAbi.flags(base.packed0()) & ~MaterialBindingAbi.FLAG_TEXTURELESS)
                | MaterialBindingAbi.FLAG_BASE_COLOR_LINEAR;
        return new MaterialBindingData(
                MaterialBindingAbi.pack(baseColorTextureIndex, MaterialBindingAbi.coverage(base.packed0()), flags,
                        MaterialBindingAbi.surfaceImplementation(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1());
    }

    /** Resolve a standalone texture to its pack-compiled binding ID. */
    public int resolveStandaloneTexture(ResourceId material, boolean stochasticCoverage) {
        int id = material != null ? runtimeTextureIds.getOrDefault(material, runtimeFallbackId)
                : runtimeFallbackId;
        return stochasticCoverage ? withStochasticCoverage(id) : id;
    }

    /** Resolve a stitched atlas reference, appending its UV surface on first use. */
    public int resolveAtlasReference(AtlasMaterialReference reference, boolean stochasticCoverage) {
        if (reference == null) return runtimeFallbackId(stochasticCoverage);
        Integer current = atlasReferenceIds.get(reference);
        if (current != null) return stochasticCoverage ? withStochasticCoverage(current) : current;
        RuntimeTemplate template = runtimeTemplates.get(reference.material());
        if (template == null) return runtimeFallbackId(stochasticCoverage);
        if (nextSurfaceId >= surfaceCapacity) {
            throw new IllegalStateException("RT surface material reserve exhausted");
        }
        int surfaceId = nextSurfaceId++;
        var uv = reference.albedoUv();
        SurfaceMaterialData surface = surface(template.desc(), template.entry(),
                uv.u(), uv.v(), uv.inverseDu(), uv.inverseDv());
        long offset = Math.multiplyExact((long) surfaceId, SurfaceMaterialData.BYTE_SIZE);
        surface.write(MemoryUtil.memByteBuffer(surfaceTable.mapped() + offset, SurfaceMaterialData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder()));
        surfaceTable.flush(offset, SurfaceMaterialData.BYTE_SIZE);
        surfaceRecords.add(surface);
        int id = intern(binding(surfaceId, template.desc(), transparentWhiteAverage(),
                SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX, RUNTIME_TEXTURE_COVERAGE_CUTOFF));
        atlasReferenceIds.put(reference, id);
        return stochasticCoverage ? withStochasticCoverage(id) : id;
    }

    /**
     * The ID of a binding with this exact content, appending it to the uploaded table on first use.
     * Content keying is what makes bindings cheap: a surface reached through a different base-color texture and
     * then a different coverage mode lands on the same ID as the reverse order, so the variant product
     * never multiplies. The render thread writes and flushes a new record before packing geometry that can
     * reference its ID; in-flight frames can reference only earlier immutable records. The table allocation
     * and device address therefore remain fixed for the whole material epoch.
     */
    private int intern(MaterialBindingData binding) {
        Integer current = bindingIds.get(binding);
        if (current != null) return current;
        if (bindingRecords.size() >= bindingCapacity) {
            throw new IllegalStateException("RT material binding reserve exhausted");
        }
        int id = bindingRecords.size();
        long offset = Math.multiplyExact((long) id, MaterialBindingData.BYTE_SIZE);
        binding.write(MemoryUtil.memByteBuffer(bindingTable.mapped() + offset, MaterialBindingData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder()));
        bindingTable.flush(offset, MaterialBindingData.BYTE_SIZE);
        bindingRecords.add(binding);
        bindingIds.put(binding, id);
        return id;
    }

    /** Caller must ensure no in-flight trace references the current table. */
    public void destroy() {
        snapshot = null;
        runtimeTextureIds = Map.of();
        namedMaterialIds = Map.of();
        runtimeTemplates = Map.of();
        atlasReferenceIds.clear();
        bindingRecords.clear();
        surfaceRecords.clear();
        bindingIds.clear();
        runtimeFallbackId = 0;
        bindingCapacity = 0;
        nextSurfaceId = 0;
        surfaceCapacity = 0;
        if (bindingTable != null) {
            bindingTable.destroy();
            bindingTable = null;
        }
        if (surfaceTable != null) {
            surfaceTable.destroy();
            surfaceTable = null;
        }
    }

    static int index(OpenPbrMaterialProfile profile, MaterialTopology topology, boolean emitting) {
        return MaterialRegistryCompiler.index(profile, topology, emitting);
    }

    static int transport(MaterialTopology topology) {
        return MaterialRegistryCompiler.transport(topology);
    }

    static int definitionFeatures(boolean textured, int pageFeatures, float emissionLuminanceCdM2) {
        int features = textured ? pageFeatures & (FEATURE_SPEC | FEATURE_NORMAL | FEATURE_EMISSION_MASK
                | FEATURE_SUBSURFACE_COLOR_BASE | FEATURE_EMISSION_COLOR_BASE) : 0;
        if (emissionLuminanceCdM2 > 0.0f) features |= FEATURE_UNIFORM_EMISSION;
        return features;
    }

    /** Texture masks own the summary; otherwise an emitting state uses the full texture. */
    private static RtMaterialDesc.EmissionSummary variantSummary(int features, boolean emitting,
                                                                 RtMaterialPageCompiler.Entry entry,
                                                                 RtMaterialDesc.EmissionSummary uniformSummary) {
        if ((features & (FEATURE_SPEC | FEATURE_EMISSION_MASK)) != 0) return entry.emissionSummary();
        return emitting ? uniformSummary : RtMaterialDesc.EmissionSummary.NONE;
    }

    private static RtMaterialDesc compileDesc(int transport, int features, OpenPbrMaterialProfile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary,
                                              float defaultEmissionLuminance) {
        return compileDesc(transport, features, profile, emitting, neutral, emissionSummary,
                OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_IOR, defaultEmissionLuminance);
    }

    private static RtMaterialDesc compileDesc(int transport, int features, OpenPbrMaterialProfile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary,
                                              float dielectricIor, float defaultEmissionLuminance) {
        return MaterialRegistryCompiler.textureDescription(transport, features, profile, emitting, neutral,
                emissionSummary, dielectricIor, defaultEmissionLuminance);
    }

    private static RtMaterialDesc compileRuntimeTextureDesc(int features, boolean neutral,
                                                    RtMaterialDesc.EmissionSummary emissionSummary,
                                                    float defaultEmissionLuminance) {
        return MaterialRegistryCompiler.runtimeTextureDescription(features, neutral, emissionSummary,
                defaultEmissionLuminance);
    }

    /**
     * The compiled tables under construction during a rebuild. Every compiled surface gets one binding —
     * plus the cutout-coverage sibling a masked geometry source asks for — so
     * the returned binding ID is what geometry stores and what {@link MaterialEpochSnapshot} indexes its descriptions,
     * emission footprints and SBT classes by. Siblings share the base's surface, description and footprint, so they
     * cost sixteen table bytes and two list slots each and keep every parallel array dense.
     */
    private static final class CompiledTables {
        final List<SurfaceMaterialData> surfaces;
        final List<MaterialBindingData> bindings;
        final List<RtMaterialDesc> descriptions;
        final List<EmissionFootprint> footprints;
        /** Parallel to {@code bindings}: the cutout-coverage variant of each ID, or the ID itself. */
        final IntArrayList cutoutVariants;

        CompiledTables(int expected) {
            surfaces = new ArrayList<>(expected);
            bindings = new ArrayList<>(expected);
            descriptions = new ArrayList<>(expected);
            footprints = new ArrayList<>(expected);
            cutoutVariants = new IntArrayList(expected);
        }

        int add(RtMaterialDesc desc, float[] average, RtMaterialPageCompiler.Entry entry,
                EmissionFootprint uniformFootprint, float coverageCutoff) {
            return add(desc, average, entry, uniformFootprint, coverageCutoff, false);
        }

        int add(RtMaterialDesc desc, float[] average, RtMaterialPageCompiler.Entry entry,
                EmissionFootprint uniformFootprint, float coverageCutoff, boolean cutoutSibling) {
            int surfaceId = surfaces.size();
            surfaces.add(surface(desc, entry, entry.albedoU(), entry.albedoV(),
                    entry.albedoInvDu(), entry.albedoInvDv()));
            int id = append(binding(surfaceId, desc, average, SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX, coverageCutoff),
                    desc, footprintFor(desc, entry, uniformFootprint));
            if (cutoutSibling) {
                cutoutVariants.set(id, addCutoutSibling(id));
            }
            return id;
        }

        int addDefinition(RtMaterialDesc desc, MaterialDefinition definition, RtMaterialPageCompiler.Entry entry) {
            int surfaceId = surfaces.size();
            surfaces.add(surfaceDefinition(desc, definition, entry));
            float[] average = definition.textures() == null
                    ? new float[]{definition.baseColorR(), definition.baseColorG(), definition.baseColorB(), 1.0f}
                    : new float[]{entry.averageR() * definition.baseColorR(),
                    entry.averageG() * definition.baseColorG(), entry.averageB() * definition.baseColorB(),
                    entry.averageA()};
            MaterialBindingData base = binding(surfaceId, desc, average,
                    SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX, definition.alphaCutoff());
            int flags = MaterialBindingAbi.flags(base.packed0()) | MaterialBindingAbi.FLAG_TEXTURELESS;
            MaterialBindingData textureless = new MaterialBindingData(
                    MaterialBindingAbi.pack(SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX, COVERAGE_OPAQUE, flags,
                            MaterialBindingAbi.surfaceImplementation(base.packed0())),
                    base.surface(), base.shadowTint(), base.packed1());
            return append(textureless, desc, footprintFor(desc, entry, null));
        }

        /** The base's binding with {@link #COVERAGE_CUTOUT}, over the same surface/description/footprint. */
        private int addCutoutSibling(int baseId) {
            MaterialBindingData base = bindings.get(baseId);
            return append(new MaterialBindingData(
                            MaterialBindingAbi.pack(MaterialBindingAbi.baseColorTextureIndex(base.packed0()), COVERAGE_CUTOUT,
                                    MaterialBindingAbi.flags(base.packed0()), MaterialBindingAbi.surfaceImplementation(base.packed0())),
                            base.surface(), base.shadowTint(), base.packed1()),
                    descriptions.get(baseId), footprints.get(baseId));
        }

        private int append(MaterialBindingData binding, RtMaterialDesc desc, EmissionFootprint footprint) {
            int id = bindings.size();
            bindings.add(binding);
            descriptions.add(desc);
            footprints.add(footprint);
            cutoutVariants.add(id); // no sibling of its own until one is compiled below
            return id;
        }
    }

    /**
     * The emission footprint whose sampled source matches what {@code world.rchit} shades for this
     * description — the same selection {@link #variantSummary}/override application made for the summary.
     */
    private static EmissionFootprint footprintFor(RtMaterialDesc desc, RtMaterialPageCompiler.Entry entry,
                                                  EmissionFootprint uniformFootprint) {
        return switch (desc.emissionSource()) {
            case AUTHORED_MASK, DERIVED_MASK -> entry.emissionFootprint();
            case GEOMETRY_UNIFORM -> uniformFootprint;
            case NONE -> null;
        };
    }

    private static SurfaceMaterialData surface(RtMaterialDesc desc, RtMaterialPageCompiler.Entry entry,
                                               float albedoU, float albedoV,
                                               float albedoInvDu, float albedoInvDv) {
        int page = (entry.pageIndex() & PAGE_MASK) | (entry.maxLod() << MAX_LOD_SHIFT);
        int alphaRange = RtMaterialTextureData.unorm8(entry.minAlpha())
                | (RtMaterialTextureData.unorm8(entry.maxAlpha()) << 8);
        return surfaceData(desc, desc.features(), page, alphaRange, entry.alphaSource(),
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(albedoU, albedoV, albedoInvDu, albedoInvDv),
                new Float4(1.0f, 1.0f, 1.0f, 1.0f));
    }

    private static SurfaceMaterialData surfaceDefinition(RtMaterialDesc desc, MaterialDefinition definition,
                                                         RtMaterialPageCompiler.Entry entry) {
        if (definition.textures() == null) {
            return surfaceData(desc, desc.features(), 0, 0xFFFF, 0,
                    new Float4(0.0f, 0.0f, 0.0f, 0.0f),
                    new Float4(0.0f, 0.0f, 1.0f, 1.0f),
                    new Float4(definition.baseColorR(), definition.baseColorG(), definition.baseColorB(), 1.0f));
        }
        int page = (entry.pageIndex() & PAGE_MASK) | (entry.maxLod() << MAX_LOD_SHIFT);
        int alphaRange = RtMaterialTextureData.unorm8(entry.minAlpha())
                | (RtMaterialTextureData.unorm8(entry.maxAlpha()) << 8);
        return surfaceData(desc, desc.features(), page, alphaRange, entry.alphaSource(),
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(entry.albedoU(), entry.albedoV(), entry.albedoInvDu(), entry.albedoInvDv()),
                new Float4(definition.baseColorR(), definition.baseColorG(), definition.baseColorB(), 1.0f));
    }

    private static SurfaceMaterialData surfaceData(RtMaterialDesc desc, int features, int page,
                                                   int alphaRange, int alphaFlags,
                                                   Float4 materialUv, Float4 baseColorUv,
                                                   Float4 baseColorFactor) {
        int luminance = Math.round(Math.min(MAX_EMISSION_LUMINANCE, desc.emissionLuminance())
                * (EMISSION_LUMINANCE_MASK / MAX_EMISSION_LUMINANCE));
        int packedFeatures = features | (luminance << EMISSION_LUMINANCE_SHIFT);
        return new SurfaceMaterialData(packedFeatures, page, alphaRange, alphaFlags,
                materialUv, baseColorUv, desc.specularRoughness(), desc.baseMetalness(),
                desc.specularIor(), desc.transmissionWeight(), baseColorFactor,
                new Float4(desc.transmissionColorR(), desc.transmissionColorG(),
                        desc.transmissionColorB(), desc.subsurfaceWeight()),
                new Float4(desc.subsurfaceColorR(), desc.subsurfaceColorG(),
                        desc.subsurfaceColorB(), desc.subsurfaceScatterAnisotropy()),
                new Float4(desc.emissionColorR(), desc.emissionColorG(), desc.emissionColorB(), 1.0f));
    }

    /**
     * The traversal binding a compiled surface gets. Coverage comes from how the surface occupies its
     * footprint and transmittance from whether light crosses it; the two are independent, so neither is
     * derived from the other. Base-color texture index zero is the shared atlas; other producers pair the
     * surface with their own texture through {@link #withBaseColorTextureIndex}.
     */
    private static MaterialBindingData binding(int surfaceId, RtMaterialDesc desc, float[] average,
                                               int baseColorTextureIndex, float coverageCutoff) {
        // Opaque by default: a material only needs COVERAGE_CUTOUT/STOCHASTIC when a producer knows its
        // footprint is genuinely masked (see withCutoutCoverage/withStochasticCoverage) — most compiled
        // fully opaque materials never call either and get the cheap no-any-hit class.
        int coverage = COVERAGE_OPAQUE;
        int flags = 0;
        int shadowTint = WHITE_SHADOW_TINT;
        switch (desc.transport()) {
            case TRANSPORT_MEDIUM_BOUNDARY -> {
                // A medium boundary covers its whole footprint, so a shadow ray samples no coverage
                // texel and takes the compiled transmittance colour.
                flags = BINDING_TRANSMISSIVE;
                shadowTint = translucentShadowTint(average);
            }
            default -> {
            }
        }
        return new MaterialBindingData(
                MaterialBindingAbi.pack(baseColorTextureIndex, coverage, flags, desc.surfaceImplementation()), surfaceId,
                shadowTint, MaterialBindingAbi.packCoverageCutoff(coverageCutoff));
    }

    /**
     * Compile-time transmittance through a translucent surface, in ACEScg, as 8:8:8.
     *
     * <p>Beer-Lambert absorption derived from how dark the whole-texture average is, scaled by its average
     * alpha (how much of the
     * texture is colorant versus see-through frame), so saturated panes darken transmitted light
     * non-linearly. The neutral term is not alpha-scaled; clear glass has a low natural alpha,
     * and folding it into the alpha-scaled term would crush exactly the clear-glass case it covers.
     */
    static int translucentShadowTint(float[] average) {
        float[] acesCg = ColorTransforms.linearBt709ToAcesCg(average[0], average[1], average[2]);
        int packed = 0;
        for (int channel = 0; channel < 3; channel++) {
            float extinction = Math.max(-(float) Math.log(Math.max(acesCg[channel], 1.0e-3f)), 0.0f);
            float transmittance = (float) Math.exp(-extinction * average[3] - TRANSLUCENT_NEUTRAL_EXTINCTION);
            packed |= Math.round(Math.clamp(transmittance, 0.0f, 1.0f) * 255.0f) << (channel * 8);
        }
        return packed;
    }

    private static float[] transparentWhiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 0.0f};
    }

    private static final class MutableCompiledOverride {
        final RtMaterialOverrides.Rule rule;
        final Map<ResourceId, int[]> ids = new HashMap<>();
        boolean matchedMaterial;

        MutableCompiledOverride(RtMaterialOverrides.Rule rule) {
            this.rule = rule;
        }

        CompiledOverrideLookup.Entry freeze() {
            int[] variants = ids.get(rule.material());
            if (variants == null || rule.geometry() == null) {
                throw new IllegalStateException("Compiled override is missing its material variants");
            }
            return new CompiledOverrideLookup.Entry(rule.material(), rule.geometry(), variants);
        }
    }

    /** First-authored geometry-rule lookup without a linear scan over unrelated material rules. */
    static final class CompiledOverrideLookup {
        private final Map<ResourceId, Map<ResourceId, int[]>> materials;

        private CompiledOverrideLookup(Map<ResourceId, Map<ResourceId, int[]>> materials) {
            this.materials = materials;
        }

        static CompiledOverrideLookup of(List<Entry> entries) {
            if (entries.isEmpty()) return new CompiledOverrideLookup(Map.of());
            Map<ResourceId, Map<ResourceId, int[]>> mutable = new HashMap<>();
            for (Entry entry : entries) {
                mutable.computeIfAbsent(entry.material, ignored -> new HashMap<>())
                        .putIfAbsent(entry.geometry, entry.variants);
            }
            Map<ResourceId, Map<ResourceId, int[]>> frozen = HashMap.newHashMap(mutable.size());
            mutable.forEach((material, geometry) ->
                    frozen.put(material, Collections.unmodifiableMap(new HashMap<>(geometry))));
            return new CompiledOverrideLookup(Collections.unmodifiableMap(frozen));
        }

        int[] resolve(ResourceId material, ResourceId geometry) {
            Map<ResourceId, int[]> entries = materials.get(material);
            return entries != null && geometry != null ? entries.get(geometry) : null;
        }

        record Entry(ResourceId material, ResourceId geometry, int[] variants) {
        }
    }

}
