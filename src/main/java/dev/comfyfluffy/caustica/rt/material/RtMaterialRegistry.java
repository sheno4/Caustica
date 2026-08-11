package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialProfile;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtColor;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
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
 * bindless albedo slot, and the uniform shadow transmittance — and names a surface. Geometry stores
 * binding IDs, so pairing one surface with another albedo slot or coverage mode is a sixteen-byte append
 * rather than a cloned material, and bindings are interned on content: the same triple is one ID however
 * it was reached.
 *
 * <p>Workers read an immutable {@link Snapshot}; shared-atlas surfaces may append into
 * pre-reserved table slots because their stitched UV rectangles only become available during capture.
 * Published records never mutate.
 */
public final class RtMaterialRegistry {
    public static final RtMaterialRegistry INSTANCE = new RtMaterialRegistry();

    // Canonical SurfaceMaterial model/feature bits, mirrored by world_common.slang's MATERIAL_* constants.
    // RtBlockMaterials.Entry.features uses the same bit values, so entry features flow into surfaces
    // with a plain mask.
    public static final int MODEL_OPAQUE = 0;
    public static final int MODEL_DIELECTRIC = 3;
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    /** A per-texel emission mask was compiled into the page; it says nothing about where it came from. */
    public static final int FEATURE_EMISSION_MASK = 4;
    /** Bindless albedo slot reserved for the host's shared atlas. */
    public static final int SHARED_ATLAS_ALBEDO_SLOT = 0;
    /**
     * The surface implementation every material compiles with unless an override names another.
     * {@code caustica:builtin} registers its own first, so index 0 is always the reference surface.
     */
    public static final int BUILTIN_SURFACE_IMPLEMENTATION = 0;

    // Coverage — is the surface present along this ray — mirrored by world_common.slang's COVERAGE_*.
    private static final int COVERAGE_OPAQUE = 0;
    private static final int COVERAGE_CUTOUT = 1;
    private static final int COVERAGE_STOCHASTIC = 2;
    // Transmittance — how much light passes where the surface is present. Mirrors BINDING_* in Slang.
    private static final int BINDING_TRANSMISSIVE = 1;
    static final int BINDING_TEXTURELESS = 4;
    // MaterialBinding.packed0 = albedoSlot:16 | coverageMode:2 | flags:6 | surfaceImpl:8;
    // packed1 = coverageCutoff:8. Mirrored by the bindingAlbedoSlot/bindingCoverage/bindingFlags/
    // bindingSurfaceImpl/bindingCutoff accessors in world_common.slang — the any-hit reads these fields
    // out of one aligned load, so the shifts are ABI.
    private static final int ALBEDO_SLOT_MASK = 0xFFFF;
    private static final int COVERAGE_SHIFT = 16;
    private static final int COVERAGE_MASK = 3;
    private static final int FLAGS_SHIFT = 18;
    private static final int FLAGS_MASK = 63;
    private static final int SURFACE_IMPL_SHIFT = 24;
    private static final int SURFACE_IMPL_MASK = 255;
    private static final int CUTOFF_MASK = 255;

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

    private static final int MODEL_VARIANTS = 2; // ordinary opaque/cutout and transparent dielectric
    private static final int EMISSION_VARIANTS = 2; // state-gated emission disabled/enabled
    private static final int VARIANT_OPAQUE = 0;
    private static final int VARIANT_GLASS = 1;
    // Source adapters map geometry onto this finite set before calling the registry.
    private static final OpenPbrMaterialProfile[] TEXTURE_PROFILES = {
            OpenPbrMaterialProfile.ROUGH_DIELECTRIC, OpenPbrMaterialProfile.CONDUCTOR,
            OpenPbrMaterialProfile.SMOOTH_DIELECTRIC, OpenPbrMaterialProfile.POLISHED_DIELECTRIC};

    private volatile Snapshot snapshot;
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
    private final Map<MaterialBindingData, Integer> bindingIds = new HashMap<>();
    private int runtimeFallbackId;
    private int particleId;
    private int bindingCapacity;
    private int nextSurfaceId;
    private int surfaceCapacity;

    private record RuntimeTemplate(RtMaterialDesc desc, RtBlockMaterials.Entry entry) {
    }

    private RtMaterialRegistry() {
    }

    /** Build and atomically publish the registry for the current resource epoch. */
    public void rebuild(GpuContext ctx, RtBlockMaterials pageCompiler, MaterialCatalog catalog,
                        RtMaterialOverrides overrides, List<MaterialDefinition> definitions,
                        RtMaterialOverrides.SurfaceResolver surfaces, int runtimeTextureCapacity) {
        Map<ResourceId, RtBlockMaterials.Entry> entries = pageCompiler.preparedEntries();
        float defaultEmissionLuminance = catalog.defaultUniformEmissionLuminanceCdM2();
        List<ResourceId> atlasAssets = catalog.atlasAssets().stream()
                .map(MaterialTextureAsset::material).toList();
        List<ResourceId> standaloneAssets = catalog.standalone().stream()
                .map(MaterialTextureAsset::material).toList();
        Map<ResourceId, MaterialTextureAsset> assets = new HashMap<>();
        catalog.atlasAssets().forEach(asset -> assets.put(asset.material(), asset));
        catalog.standalone().forEach(asset -> assets.put(asset.material(), asset));
        RtBlockMaterials.Entry fallbackEntry = pageCompiler.entry(null);

        int profileVariants = TEXTURE_PROFILES.length * MODEL_VARIANTS * EMISSION_VARIANTS;
        CompiledTables tables = new CompiledTables(2 + profileVariants + atlasAssets.size() * profileVariants);
        tables.add(compileDesc(MODEL_OPAQUE, 0, OpenPbrMaterialProfile.ROUGH_DIELECTRIC, false, true,
                RtMaterialDesc.EmissionSummary.NONE, defaultEmissionLuminance), transparentWhiteAverage(), fallbackEntry, null,
                PRIMARY_COVERAGE_CUTOFF, true);
        int[] fallbackVariants = new int[profileVariants];
        for (OpenPbrMaterialProfile profile : TEXTURE_PROFILES) {
            for (boolean glass : new boolean[]{false, true}) {
                for (boolean emitting : new boolean[]{false, true}) {
                    int variant = index(profile, glass, emitting);
                    if (profile.equals(OpenPbrMaterialProfile.ROUGH_DIELECTRIC) && !glass && !emitting) {
                        fallbackVariants[variant] = 0;
                        continue;
                    }
                    fallbackVariants[variant] = tables.add(
                            compileDesc(glass ? MODEL_DIELECTRIC : MODEL_OPAQUE, 0, profile, emitting, true,
                                    RtMaterialDesc.EmissionSummary.NONE, defaultEmissionLuminance),
                            transparentWhiteAverage(), fallbackEntry, null, PRIMARY_COVERAGE_CUTOFF, !glass);
                }
            }
        }
        int defaultUniformEmissionId = tables.add(compileDesc(MODEL_OPAQUE, 0, OpenPbrMaterialProfile.MEDIUM_ROUGH_DIELECTRIC,
                true, true,
                uniformWhiteSummary(), defaultEmissionLuminance), whiteAverage(), fallbackEntry,
                albedoGridFor(entries, catalog.defaultUniformEmissionAsset()), PRIMARY_COVERAGE_CUTOFF);
        int nextRuntimeFallbackId = tables.add(
                compileRuntimeTextureDesc(0, true, RtMaterialDesc.EmissionSummary.NONE,
                        defaultEmissionLuminance),
                transparentWhiteAverage(), fallbackEntry, null, RUNTIME_TEXTURE_COVERAGE_CUTOFF);
        // A billboard's footprint is genuinely masked — the texture's transparent border is absence, not a
        // clear surface — so the particle binding IS the cutout-coverage one. Compiling it that way keeps
        // the SBT class derivable from the binding at the one producer that has no render type to ask.
        int nextParticleId = tables.cutoutVariants.getInt(
                tables.add(compileParticleDesc(), transparentWhiteAverage(), fallbackEntry,
                        null, RUNTIME_TEXTURE_COVERAGE_CUTOFF, true));

        Map<ResourceId, int[]> ids = new HashMap<>();
        List<MutableCompiledOverride> compiledOverrides = new ArrayList<>();
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            compiledOverrides.add(new MutableCompiledOverride(rule));
        }
        for (ResourceId material : atlasAssets) {
            RtBlockMaterials.Entry entry = entries.get(material);
            int baseFeatures = entry.features()
                    & (FEATURE_SPEC | FEATURE_NORMAL | FEATURE_EMISSION_MASK);

            // The first material-wide rule owns every geometry use of this material.
            MutableCompiledOverride materialWide = null;
            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (compiled.rule.geometry() == null && compiled.rule.matchesMaterial(material)) {
                    materialWide = compiled;
                    compiled.matchedMaterial = true;
                    break;
                }
            }
            float dielectricIor = assets.get(material).dielectricIor();
            int[] variants = new int[profileVariants];
            for (OpenPbrMaterialProfile profile : TEXTURE_PROFILES) {
                for (boolean glass : new boolean[]{false, true}) {
                    for (boolean emitting : new boolean[]{false, true}) {
                        int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_EMISSION_MASK;
                        RtMaterialDesc desc = compileDesc(glass ? MODEL_DIELECTRIC : MODEL_OPAQUE, features,
                                profile, emitting, false,
                                variantSummary(features, emitting, entry, entry.uniformEmissionSummary()),
                                dielectricIor, defaultEmissionLuminance);
                        if (materialWide != null) {
                            desc = materialWide.rule.apply(desc);
                        }
                        variants[index(profile, glass, emitting)] = tables.add(desc, entry.average(),
                                entry, entry.albedoGrid(), PRIMARY_COVERAGE_CUTOFF, !glass);
                    }
                }
            }
            ids.put(material, variants);

            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (compiled.rule.geometry() == null || !compiled.rule.matchesMaterial(material)) continue;
                int[] overrideVariants = new int[profileVariants];
                for (OpenPbrMaterialProfile profile : TEXTURE_PROFILES) {
                    for (boolean glass : new boolean[]{false, true}) {
                        for (boolean emitting : new boolean[]{false, true}) {
                            int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_EMISSION_MASK;
                            RtMaterialDesc base = compileDesc(glass ? MODEL_DIELECTRIC : MODEL_OPAQUE,
                                    features, profile, emitting, false,
                                    variantSummary(features, emitting, entry, entry.uniformEmissionSummary()),
                                    dielectricIor, defaultEmissionLuminance);
                            RtMaterialDesc desc = compiled.rule.apply(base);
                            overrideVariants[index(profile, glass, emitting)] = tables.add(desc,
                                    entry.average(), entry, entry.albedoGrid(), PRIMARY_COVERAGE_CUTOFF,
                                    !glass);
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
            RtBlockMaterials.Entry entry = entries.get(material);
            int features = entry.features() & (FEATURE_SPEC | FEATURE_NORMAL);
            RtMaterialDesc desc = compileRuntimeTextureDesc(features, false, entry.emissionSummary(),
                    defaultEmissionLuminance);
            for (RtMaterialOverrides.Rule rule : overrides.rules()) {
                if (!rule.matches(material, null)) continue;
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
            int model = definition.transmissionWeight() > 0.0f ? MODEL_DIELECTRIC : MODEL_OPAQUE;
            RtMaterialDesc desc = new RtMaterialDesc(model, RtMaterialDesc.Source.NEUTRAL, 0,
                    definition.specularRoughness(), definition.baseMetalness(), definition.specularIor(),
                    definition.transmissionWeight(), RtMaterialDesc.EmissionSource.NONE, 0.0f,
                    RtMaterialDesc.EmissionSummary.NONE, surfaceImplementation);
            for (RtMaterialOverrides.Rule rule : overrides.rules()) {
                if (!rule.matches(definition.id(), null)) continue;
                desc = rule.apply(desc);
                runtimeMatchedOverrides.add(rule);
                break;
            }
            int id = tables.addDefinition(desc, definition);
            nextNamedMaterialIds.put(definition.id(), id);
        }

        // Standalone textures have fixed UVs. Shared-atlas references append one surface with the
        // stitched UV rectangle supplied by the host at capture time.
        int surfaceCount = tables.surfaces.size();
        int nextSurfaceCapacity = Math.addExact(surfaceCount, Math.max(64, atlasAssets.size()));
        // Bindings are appended per surface, albedo slot, and coverage mode actually submitted.
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
        List<RtEmissionGrid> grids = tables.grids;
        List<CompiledOverride> frozenOverrides = compiledOverrides.stream()
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
        Snapshot next = new Snapshot(epoch, Collections.unmodifiableMap(ids), fallbackVariants,
                defaultUniformEmissionId, defaultEmissionLuminance,
                Collections.unmodifiableMap(new HashMap<>(nextNamedMaterialIds)),
                List.copyOf(descriptions), Collections.unmodifiableList(new ArrayList<>(grids)), frozenOverrides,
                tables.cutoutVariants.toIntArray(), sbtClasses);
        runtimeTextureIds = Collections.unmodifiableMap(nextRuntimeTextureIds);
        namedMaterialIds = Collections.unmodifiableMap(nextNamedMaterialIds);
        runtimeTemplates = Collections.unmodifiableMap(nextRuntimeTemplates);
        atlasReferenceIds.clear();
        bindingRecords.clear();
        bindingRecords.addAll(tables.bindings);
        bindingIds.clear();
        for (int i = 0; i < bindingRecords.size(); i++) {
            bindingIds.put(bindingRecords.get(i), i);
        }
        runtimeFallbackId = nextRuntimeFallbackId;
        particleId = nextParticleId;
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
        return ctx.createBuffer(byteSize, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, label);
    }

    private static <T> void writeRecords(GpuBuffer table, List<T> records, int stride,
                                         java.util.function.BiConsumer<T, ByteBuffer> writer) {
        ByteBuffer mapped = MemoryUtil.memByteBuffer(table.mapped, records.size() * stride)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < records.size(); i++) {
            writer.accept(records.get(i), mapped.slice(i * stride, stride).order(ByteOrder.nativeOrder()));
        }
        table.flush();
    }

    public Snapshot requireSnapshot() {
        Snapshot current = snapshot;
        if (current == null) throw new IllegalStateException("RT terrain materials are not prepared");
        return current;
    }

    /** Resolve a stable extension material name to the current resource epoch's private binding ID. */
    public int bindingId(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }

    public long epoch() {
        Snapshot current = snapshot;
        return current != null ? current.epoch() : 0L;
    }

    public boolean isReady() {
        return snapshot != null && bindingTable != null;
    }

    /** Address of the {@code MaterialBinding} table, indexed by the material ID geometry records carry. */
    public long bindingTableAddress() {
        GpuBuffer current = bindingTable;
        if (current == null) throw new IllegalStateException("RT material binding table is not uploaded");
        return current.deviceAddress;
    }

    /** Address of the {@code SurfaceMaterial} table, indexed by {@code MaterialBinding.surface}. */
    public long surfaceTableAddress() {
        GpuBuffer current = surfaceTable;
        if (current == null) throw new IllegalStateException("RT surface material table is not uploaded");
        return current.deviceAddress;
    }

    /** Neutral canonical material used by runtime-only textures. */
    public int runtimeFallbackId() {
        return runtimeFallbackId;
    }

    public int runtimeFallbackId(boolean stochasticCoverage) {
        return stochasticCoverage ? withStochasticCoverage(runtimeFallbackId) : runtimeFallbackId;
    }

    /**
     * The binding every particle billboard submits with. A billboard is a thin surface with nothing
     * behind it, so it needs no path of its own — its material says so, and the shading that follows is
     * the same one every other surface gets. Compiled with cutout coverage (the texture's transparent
     * border is absence, not a clear surface), so a caller that isn't blending needs no derivation and
     * the SBT class falls out of {@link #sbtClassFor} like every other producer's.
     */
    public int particleId(boolean stochasticCoverage) {
        return stochasticCoverage ? withStochasticCoverage(particleId) : particleId;
    }

    /**
     * The binding resolving {@code bindingId}'s surface with stochastic coverage. A blended submission
     * decides presence with white noise whatever cutoff the material compiled, so this overrides the
     * coverage axis and leaves everything else — surface, albedo slot, transmittance — alone.
     */
    public synchronized int withStochasticCoverage(int bindingId) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(new MaterialBindingData(
                packBinding0(bindingAlbedoSlot(base.packed0()), COVERAGE_STOCHASTIC,
                        bindingFlags(base.packed0()), bindingSurfaceImpl(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1()));
    }

    /**
     * The binding resolving {@code bindingId}'s surface with deterministic cutout coverage. Compiled
     * materials default to {@code COVERAGE_OPAQUE} (see {@link #binding}); a producer that knows its
     * footprint is genuinely masked calls this
     * to get the alpha-tested variant instead. Cheap: coverage lives in the separately-interned
     * {@code MaterialBindingData}, so this never touches the precompiled surface/profile-variant table.
     */
    public synchronized int withCutoutCoverage(int bindingId) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(new MaterialBindingData(
                packBinding0(bindingAlbedoSlot(base.packed0()), COVERAGE_CUTOUT,
                        bindingFlags(base.packed0()), bindingSurfaceImpl(base.packed0())),
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

    private static int sbtClassOf(MaterialBindingData binding) {
        if (bindingCoverage(binding.packed0()) != COVERAGE_OPAQUE) return RtAccel.CLASS_MASKED;
        int flags = bindingFlags(binding.packed0());
        return (flags & BINDING_TRANSMISSIVE) != 0 ? RtAccel.CLASS_TRANSMISSIVE : RtAccel.CLASS_OPAQUE;
    }

    /**
     * The binding pairing {@code bindingId}'s surface with bindless albedo slot {@code albedoSlot}.
     */
    public synchronized int withAlbedoSlot(int bindingId, int albedoSlot) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(new MaterialBindingData(
                packBinding0(albedoSlot, bindingCoverage(base.packed0()), bindingFlags(base.packed0()),
                        bindingSurfaceImpl(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1()));
    }

    /** Resolve a standalone texture to its pack-compiled binding ID. */
    public int resolveStandaloneTexture(ResourceId material, boolean stochasticCoverage) {
        int id = material != null ? runtimeTextureIds.getOrDefault(material, runtimeFallbackId)
                : runtimeFallbackId;
        return stochasticCoverage ? withStochasticCoverage(id) : id;
    }

    /** Resolve a host atlas reference, appending its stitched UV surface on first use. */
    public synchronized int resolveAtlasReference(AtlasMaterialReference reference,
                                                  boolean stochasticCoverage) {
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
        surface.write(MemoryUtil.memByteBuffer(surfaceTable.mapped + offset, SurfaceMaterialData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder()));
        surfaceTable.flush(offset, SurfaceMaterialData.BYTE_SIZE);
        int id = intern(binding(surfaceId, template.desc(), transparentWhiteAverage(),
                SHARED_ATLAS_ALBEDO_SLOT, RUNTIME_TEXTURE_COVERAGE_CUTOFF));
        atlasReferenceIds.put(reference, id);
        return stochasticCoverage ? withStochasticCoverage(id) : id;
    }

    /**
     * The ID of a binding with this exact content, appending it to the uploaded table on first use.
     * Content keying is what makes bindings cheap: a surface reached through a different albedo slot and
     * then a different coverage mode lands on the same ID as the reverse order, so the variant product
     * never multiplies.
     */
    private int intern(MaterialBindingData binding) {
        Integer current = bindingIds.get(binding);
        if (current != null) return current;
        if (bindingRecords.size() >= bindingCapacity) {
            throw new IllegalStateException("RT material binding reserve exhausted");
        }
        int id = bindingRecords.size();
        long offset = Math.multiplyExact((long) id, MaterialBindingData.BYTE_SIZE);
        binding.write(MemoryUtil.memByteBuffer(bindingTable.mapped + offset, MaterialBindingData.BYTE_SIZE)
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
        bindingIds.clear();
        runtimeFallbackId = 0;
        particleId = 0;
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

    static int index(OpenPbrMaterialProfile profile, boolean glass, boolean emitting) {
        int p = -1;
        for (int i = 0; i < TEXTURE_PROFILES.length; i++) {
            if (TEXTURE_PROFILES[i].equals(profile)) {
                p = i;
                break;
            }
        }
        if (p < 0) throw new IllegalArgumentException("Unsupported texture material profile " + profile);
        return (p * MODEL_VARIANTS + (glass ? VARIANT_GLASS : VARIANT_OPAQUE))
                * EMISSION_VARIANTS + (emitting ? 1 : 0);
    }

    /** Texture masks own the summary; otherwise an emitting state uses the full texture. */
    private static RtMaterialDesc.EmissionSummary variantSummary(int features, boolean emitting,
                                                                 RtBlockMaterials.Entry entry,
                                                                 RtMaterialDesc.EmissionSummary uniformSummary) {
        if ((features & (FEATURE_SPEC | FEATURE_EMISSION_MASK)) != 0) return entry.emissionSummary();
        return emitting ? uniformSummary : RtMaterialDesc.EmissionSummary.NONE;
    }

    private static RtMaterialDesc compileDesc(int model, int features, OpenPbrMaterialProfile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary,
                                              float defaultEmissionLuminance) {
        return compileDesc(model, features, profile, emitting, neutral, emissionSummary,
                OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_IOR, defaultEmissionLuminance);
    }

    private static RtMaterialDesc compileDesc(int model, int features, OpenPbrMaterialProfile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary,
                                              float dielectricIor, float defaultEmissionLuminance) {
        float roughness = model == MODEL_DIELECTRIC
                ? OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS
                : profile.specularRoughness();
        float metalness = model == MODEL_DIELECTRIC ? 0.0f : profile.baseMetalness();
        // Refractive index is per material, not per model: ice and window glass are both
        // MODEL_DIELECTRIC but bend light by measurably different amounts.
        float ior = model == MODEL_DIELECTRIC
                ? dielectricIor : OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR;
        float transmission = model == MODEL_DIELECTRIC ? 1.0f : 0.0f;
        boolean authored = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (authored ? RtMaterialDesc.Source.AUTHORED_TEXTURE : RtMaterialDesc.Source.DERIVED_TEXTURE);
        RtMaterialDesc.EmissionSource emissionSource;
        if ((features & FEATURE_SPEC) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.AUTHORED_MASK;
        } else if ((features & FEATURE_EMISSION_MASK) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.DERIVED_MASK;
        } else if (emitting) {
            emissionSource = RtMaterialDesc.EmissionSource.GEOMETRY_UNIFORM;
        } else {
            emissionSource = RtMaterialDesc.EmissionSource.NONE;
        }
        float emissionLuminance = emissionSource == RtMaterialDesc.EmissionSource.NONE
                ? 0.0f : defaultEmissionLuminance;
        return new RtMaterialDesc(model, source, features, roughness, metalness, ior, transmission,
                emissionSource, emissionLuminance, emissionSummary, BUILTIN_SURFACE_IMPLEMENTATION);
    }

    /**
     * A particle billboard: fully rough, no reflectance, and a transmission weight at the symmetric point
     * so light from either side scatters identically. Index 1 is what makes the specular lobe vanish
     * rather than merely darken — a camera-facing sheet has no interface to reflect off, and Fresnel at
     * a grazing angle would otherwise give it a rim it was never meant to have.
     */
    private static RtMaterialDesc compileParticleDesc() {
        return new RtMaterialDesc(MODEL_OPAQUE, RtMaterialDesc.Source.NEUTRAL, 0, 1.0f, 0.0f, 1.0f, 0.5f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE,
                BUILTIN_SURFACE_IMPLEMENTATION);
    }

    private static RtMaterialDesc compileRuntimeTextureDesc(int features, boolean neutral,
                                                    RtMaterialDesc.EmissionSummary emissionSummary,
                                                    float defaultEmissionLuminance) {
        boolean authored = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (authored ? RtMaterialDesc.Source.AUTHORED_TEXTURE : RtMaterialDesc.Source.DERIVED_TEXTURE);
        RtMaterialDesc.EmissionSource emissionSource = (features & FEATURE_SPEC) != 0
                ? RtMaterialDesc.EmissionSource.AUTHORED_MASK : RtMaterialDesc.EmissionSource.NONE;
        float emissionLuminance = emissionSource == RtMaterialDesc.EmissionSource.NONE
                ? 0.0f : defaultEmissionLuminance;
        return new RtMaterialDesc(MODEL_OPAQUE, source, features,
                OpenPbrMaterialDefaults.RUNTIME_TEXTURE_SPECULAR_ROUGHNESS, 0.0f,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, 0.0f,
                emissionSource, emissionLuminance, emissionSummary,
                BUILTIN_SURFACE_IMPLEMENTATION);
    }

    /**
     * The compiled tables under construction during a rebuild. Every compiled surface gets one binding —
     * plus, for terrain-reachable materials, the cutout-coverage sibling a masked producer asks for — so
     * the returned binding ID is what geometry stores and what {@link Snapshot} indexes its descriptions,
     * emission grids and SBT classes by. Siblings share the base's surface, description and grid, so they
     * cost sixteen table bytes and two list slots each and keep every parallel array dense.
     */
    private static final class CompiledTables {
        final List<SurfaceMaterialData> surfaces;
        final List<MaterialBindingData> bindings;
        final List<RtMaterialDesc> descriptions;
        final List<RtEmissionGrid> grids;
        /** Parallel to {@code bindings}: the cutout-coverage variant of each ID, or the ID itself. */
        final IntArrayList cutoutVariants;

        CompiledTables(int expected) {
            surfaces = new ArrayList<>(expected);
            bindings = new ArrayList<>(expected);
            descriptions = new ArrayList<>(expected);
            grids = new ArrayList<>(expected);
            cutoutVariants = new IntArrayList(expected);
        }

        int add(RtMaterialDesc desc, float[] average, RtBlockMaterials.Entry entry,
                RtEmissionGrid uniformGrid, float coverageCutoff) {
            return add(desc, average, entry, uniformGrid, coverageCutoff, false);
        }

        int add(RtMaterialDesc desc, float[] average, RtBlockMaterials.Entry entry,
                RtEmissionGrid uniformGrid, float coverageCutoff, boolean cutoutSibling) {
            int surfaceId = surfaces.size();
            surfaces.add(surface(desc, entry, entry.albedoU(), entry.albedoV(),
                    entry.albedoInvDu(), entry.albedoInvDv()));
            int id = append(binding(surfaceId, desc, average, SHARED_ATLAS_ALBEDO_SLOT, coverageCutoff),
                    desc, gridFor(desc, entry, uniformGrid));
            if (cutoutSibling) {
                cutoutVariants.set(id, addCutoutSibling(id));
            }
            return id;
        }

        int addDefinition(RtMaterialDesc desc, MaterialDefinition definition) {
            int surfaceId = surfaces.size();
            surfaces.add(surfaceDefinition(desc, definition));
            float[] average = {definition.baseColorR(), definition.baseColorG(), definition.baseColorB(), 1.0f};
            MaterialBindingData base = binding(surfaceId, desc, average,
                    SHARED_ATLAS_ALBEDO_SLOT, RUNTIME_TEXTURE_COVERAGE_CUTOFF);
            int flags = bindingFlags(base.packed0()) | BINDING_TEXTURELESS;
            MaterialBindingData textureless = new MaterialBindingData(
                    packBinding0(SHARED_ATLAS_ALBEDO_SLOT, COVERAGE_OPAQUE, flags,
                            bindingSurfaceImpl(base.packed0())),
                    base.surface(), base.shadowTint(), base.packed1());
            return append(textureless, desc, null);
        }

        /** The base's binding with {@link #COVERAGE_CUTOUT}, over the same surface/description/grid. */
        private int addCutoutSibling(int baseId) {
            MaterialBindingData base = bindings.get(baseId);
            return append(new MaterialBindingData(
                            packBinding0(bindingAlbedoSlot(base.packed0()), COVERAGE_CUTOUT,
                                    bindingFlags(base.packed0()), bindingSurfaceImpl(base.packed0())),
                            base.surface(), base.shadowTint(), base.packed1()),
                    descriptions.get(baseId), grids.get(baseId));
        }

        private int append(MaterialBindingData binding, RtMaterialDesc desc, RtEmissionGrid grid) {
            int id = bindings.size();
            bindings.add(binding);
            descriptions.add(desc);
            grids.add(grid);
            cutoutVariants.add(id); // no sibling of its own until one is compiled below
            return id;
        }
    }

    /**
     * The emission grid whose per-texel source matches what {@code world.rchit} shades for this
     * description — the same selection {@link #variantSummary}/override application made for the summary.
     */
    private static RtEmissionGrid gridFor(RtMaterialDesc desc, RtBlockMaterials.Entry entry,
                                          RtEmissionGrid uniformGrid) {
        return switch (desc.emissionSource()) {
            case AUTHORED_MASK, DERIVED_MASK -> entry.emissionGrid();
            case GEOMETRY_UNIFORM -> uniformGrid;
            case NONE -> null;
        };
    }

    private static RtEmissionGrid albedoGridFor(Map<ResourceId, RtBlockMaterials.Entry> entries,
                                                ResourceId reference) {
        RtBlockMaterials.Entry entry = reference != null ? entries.get(reference) : null;
        return entry != null ? entry.albedoGrid() : null;
    }

    private static SurfaceMaterialData surface(RtMaterialDesc desc, RtBlockMaterials.Entry entry,
                                               float albedoU, float albedoV,
                                               float albedoInvDu, float albedoInvDv) {
        // Packed unconditionally (0 for non-emissive materials): the shader multiplies the emission mask
        // by this every time, regardless of source, so the package baseline needs no shader copy.
        int luminance = Math.round(Math.min(MAX_EMISSION_LUMINANCE, desc.emissionLuminance())
                * (EMISSION_LUMINANCE_MASK / MAX_EMISSION_LUMINANCE));
        int packedFeatures = desc.features() | (luminance << EMISSION_LUMINANCE_SHIFT);
        int page = (entry.pageIndex() & PAGE_MASK) | (entry.maxLod() << MAX_LOD_SHIFT);
        return new SurfaceMaterialData(packedFeatures, page,
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(albedoU, albedoV, albedoInvDu, albedoInvDv),
                desc.specularRoughness(), desc.baseMetalness(), desc.specularIor(),
                desc.transmissionWeight());
    }

    private static SurfaceMaterialData surfaceDefinition(RtMaterialDesc desc, MaterialDefinition definition) {
        return new SurfaceMaterialData(0, 0, new Float4(0.0f, 0.0f, 0.0f, 0.0f),
                new Float4(definition.baseColorR(), definition.baseColorG(), definition.baseColorB(), 1.0f),
                desc.specularRoughness(), desc.baseMetalness(), desc.specularIor(), desc.transmissionWeight());
    }

    /**
     * The traversal binding a compiled surface gets. Coverage comes from how the surface occupies its
     * footprint and transmittance from whether light crosses it; the two are independent, so neither is
     * derived from the other. Slot 0 is the shared atlas; other producers pair the same surface with
     * their own slot through {@link #withAlbedoSlot}.
     */
    private static MaterialBindingData binding(int surfaceId, RtMaterialDesc desc, float[] average,
                                               int albedoSlot, float coverageCutoff) {
        // Opaque by default: a material only needs COVERAGE_CUTOUT/STOCHASTIC when a producer knows its
        // footprint is genuinely masked (see withCutoutCoverage/withStochasticCoverage) — most compiled
        // materials, including solid terrain, never call either and get the cheap no-any-hit class.
        int coverage = COVERAGE_OPAQUE;
        int flags = 0;
        int shadowTint = WHITE_SHADOW_TINT;
        switch (desc.model()) {
            case MODEL_DIELECTRIC -> {
                // Glass covers its whole footprint — the see-through part of the texture is clear glass,
                // not absence — so a shadow ray samples no texel and takes the compiled colour.
                flags = BINDING_TRANSMISSIVE;
                shadowTint = translucentShadowTint(average);
            }
            default -> {
            }
        }
        return new MaterialBindingData(
                packBinding0(albedoSlot, coverage, flags, desc.surfaceImplementation()), surfaceId,
                shadowTint, packCoverageCutoff(coverageCutoff));
    }

    static int packBinding0(int albedoSlot, int coverageMode, int flags, int surfaceImplementation) {
        return (albedoSlot & ALBEDO_SLOT_MASK) | ((coverageMode & COVERAGE_MASK) << COVERAGE_SHIFT)
                | ((flags & FLAGS_MASK) << FLAGS_SHIFT)
                | ((surfaceImplementation & SURFACE_IMPL_MASK) << SURFACE_IMPL_SHIFT);
    }

    static int bindingSurfaceImpl(int packed0) {
        return (packed0 >>> SURFACE_IMPL_SHIFT) & SURFACE_IMPL_MASK;
    }

    static int bindingAlbedoSlot(int packed0) {
        return packed0 & ALBEDO_SLOT_MASK;
    }

    static int bindingCoverage(int packed0) {
        return (packed0 >>> COVERAGE_SHIFT) & COVERAGE_MASK;
    }

    static int bindingFlags(int packed0) {
        return (packed0 >>> FLAGS_SHIFT) & FLAGS_MASK;
    }

    /** The alpha test threshold as the shader reads it back: an 8-bit unorm of the authored cutoff. */
    static int packCoverageCutoff(float cutoff) {
        return Math.round(cutoff * CUTOFF_MASK) & CUTOFF_MASK;
    }

    static float unpackCoverageCutoff(int packed1) {
        return (packed1 & CUTOFF_MASK) / (float) CUTOFF_MASK;
    }

    /**
     * Compile-time transmittance through a translucent surface, in ACEScg, as 8:8:8.
     *
     * <p>Beer-Lambert absorption matching the water medium in {@code world.rgen}: a per-channel extinction
     * derived from how dark the whole-texture average is, scaled by its average alpha (how much of the
     * texture is colorant versus see-through frame), so saturated panes darken transmitted light
     * non-linearly. The neutral term is not alpha-scaled; clear glass has a low natural alpha,
     * and folding it into the alpha-scaled term would crush exactly the clear-glass case it covers.
     */
    static int translucentShadowTint(float[] average) {
        float[] acesCg = RtColor.linearBt709ToAcesCg(average[0], average[1], average[2]);
        int packed = 0;
        for (int channel = 0; channel < 3; channel++) {
            float extinction = Math.max(-(float) Math.log(Math.max(acesCg[channel], 1.0e-3f)), 0.0f);
            float transmittance = (float) Math.exp(-extinction * average[3] - TRANSLUCENT_NEUTRAL_EXTINCTION);
            packed |= Math.round(Math.clamp(transmittance, 0.0f, 1.0f) * 255.0f) << (channel * 8);
        }
        return packed;
    }

    private static float[] whiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    }

    private static float[] transparentWhiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 0.0f};
    }

    private static RtMaterialDesc.EmissionSummary uniformWhiteSummary() {
        return new RtMaterialDesc.EmissionSummary(1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static final class MutableCompiledOverride {
        final RtMaterialOverrides.Rule rule;
        final Map<ResourceId, int[]> ids = new HashMap<>();
        boolean matchedMaterial;

        MutableCompiledOverride(RtMaterialOverrides.Rule rule) {
            this.rule = rule;
        }

        CompiledOverride freeze() {
            return new CompiledOverride(rule, Collections.unmodifiableMap(new HashMap<>(ids)));
        }
    }

    private record CompiledOverride(RtMaterialOverrides.Rule rule, Map<ResourceId, int[]> ids) {
    }

    /** Read-only lookup captured once by a terrain task. */
    public static final class Snapshot {
        private final long epoch;
        private final Map<ResourceId, int[]> ids;
        private final int[] fallbackVariants;
        private final int defaultUniformEmissionId;
        private final float defaultUniformEmissionLuminanceCdM2;
        private final Map<ResourceId, Integer> namedMaterialIds;
        private final List<RtMaterialDesc> descriptions;
        private final List<RtEmissionGrid> grids;
        private final List<CompiledOverride> overrides;
        private final int[] cutoutVariants;
        private final byte[] sbtClasses;

        private Snapshot(long epoch, Map<ResourceId, int[]> ids, int[] fallbackVariants,
                         int defaultUniformEmissionId, float defaultUniformEmissionLuminanceCdM2,
                         Map<ResourceId, Integer> namedMaterialIds,
                         List<RtMaterialDesc> descriptions,
                         List<RtEmissionGrid> grids, List<CompiledOverride> overrides,
                         int[] cutoutVariants, byte[] sbtClasses) {
            this.epoch = epoch;
            this.ids = ids;
            this.fallbackVariants = fallbackVariants;
            this.defaultUniformEmissionId = defaultUniformEmissionId;
            this.defaultUniformEmissionLuminanceCdM2 = defaultUniformEmissionLuminanceCdM2;
            this.namedMaterialIds = namedMaterialIds;
            this.descriptions = descriptions;
            this.grids = grids;
            this.overrides = overrides;
            this.cutoutVariants = cutoutVariants;
            this.sbtClasses = sbtClasses;
        }

        public long epoch() {
            return epoch;
        }

        public int defaultUniformEmissionId() {
            return defaultUniformEmissionId;
        }

        public float defaultUniformEmissionLuminanceCdM2() {
            return defaultUniformEmissionLuminanceCdM2;
        }

        /** Resolve a stable name inside this immutable resource epoch. */
        public int bindingId(ResourceId material) {
            Integer id = namedMaterialIds.get(material);
            if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
            return id;
        }

        public int materialCount() {
            return descriptions.size();
        }

        public RtMaterialDesc material(int materialId) {
            return descriptions.get(materialId);
        }

        /** Emission summary grid matching this material's shaded emission source, or null when none. */
        public RtEmissionGrid emissionGrid(int materialId) {
            return grids.get(materialId);
        }

        /**
         * The snapshot-local equivalent of {@link RtMaterialRegistry#withCutoutCoverage}: the ID resolving
         * this material with deterministic cutout coverage, precompiled at rebuild so a terrain worker
         * never interns into the live registry. IDs without a compiled sibling (dielectric/fluid
         * variants, which no masked producer reaches) map to themselves.
         */
        public int withCutoutCoverage(int materialId) {
            return cutoutVariants[materialId];
        }

        /** The SBT hit-group class of a snapshot material — see {@link RtMaterialRegistry#sbtClassFor}. */
        public int sbtClassFor(int materialId) {
            return sbtClasses[materialId];
        }

        public int resolve(ResourceId material, ResourceId geometry, MaterialVariant materialVariant) {
            int variant = index(materialVariant.profile(), materialVariant.transmissive(),
                    materialVariant.emitting());
            for (CompiledOverride override : overrides) {
                if (!override.rule.matches(material, geometry)) continue;
                int[] variants = override.ids.get(material);
                if (variants != null) return variants[variant];
            }
            int[] variants = ids.get(material);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }

        /** Stateless resolve for shared-atlas geometry. */
        public int resolve(ResourceId material, OpenPbrMaterialProfile profile,
                           boolean glass, boolean emitting) {
            int[] variants = ids.get(material);
            int variant = index(profile, glass, emitting);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }
    }
}
