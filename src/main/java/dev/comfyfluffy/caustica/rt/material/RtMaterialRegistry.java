package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtColor;
import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.gen.MaterialBindingData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData.Float4;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource-epoch material registry shared by terrain, entities, block entities and item geometry.
 *
 * <p>The compiled record is two tables. {@link SurfaceMaterialData} carries the closest-hit surface
 * parameters; {@link MaterialBindingData} carries the sixteen bytes traversal reads — coverage mode,
 * bindless albedo slot, and the uniform shadow transmittance — and names a surface. Geometry stores
 * binding IDs, so pairing one surface with another albedo slot or coverage mode is a sixteen-byte append
 * rather than a cloned material, and bindings are interned on content: the same triple is one ID however
 * it was reached.
 *
 * <p>Terrain workers read an immutable {@link Snapshot}; entity atlas surfaces may append into
 * pre-reserved table slots because their stitched UV rectangles only become available during capture.
 * Published records never mutate.
 */
public final class RtMaterialRegistry {
    public static final RtMaterialRegistry INSTANCE = new RtMaterialRegistry();

    // Canonical SurfaceMaterial model/feature bits, mirrored by world_common.slang's MATERIAL_* constants.
    // RtBlockMaterials.Entry.features uses the same bit values, so entry features flow into surfaces
    // with a plain mask.
    public static final int MODEL_OPAQUE = 0;
    public static final int MODEL_WATER = 1;
    public static final int MODEL_DIELECTRIC = 3;
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    public static final int FEATURE_HEURISTIC_EMISSION = 4;
    /** Bindless albedo slot reserved for the vanilla block atlas, seeded by {@code RtEntityTextures}. */
    public static final int BLOCK_ATLAS_ALBEDO_SLOT = 0;

    // Coverage — is the surface present along this ray — mirrored by world_common.slang's COVERAGE_*.
    private static final int COVERAGE_OPAQUE = 0;
    private static final int COVERAGE_CUTOUT = 1;
    private static final int COVERAGE_STOCHASTIC = 2;
    // Transmittance — how much light passes where the surface is present. Mirrors BINDING_* in Slang.
    private static final int BINDING_TRANSMISSIVE = 1;
    private static final int BINDING_RECORD_CROSSING = 2;
    // MaterialBinding.packed0 = albedoSlot:16 | coverageMode:2 | flags:6; packed1 = coverageCutoff:8.
    // Mirrored by the bindingAlbedoSlot/bindingCoverage/bindingFlags/bindingCutoff accessors in
    // world_common.slang — the any-hit reads these fields out of one aligned load, so the shifts are ABI.
    private static final int ALBEDO_SLOT_MASK = 0xFFFF;
    private static final int COVERAGE_SHIFT = 16;
    private static final int COVERAGE_MASK = 3;
    private static final int FLAGS_SHIFT = 18;
    private static final int FLAGS_MASK = 63;
    private static final int CUTOFF_MASK = 255;

    // Coverage cutoffs, packed into MaterialBinding.packed1 so no any-hit branches on the producer.
    private static final float TERRAIN_COVERAGE_CUTOFF = 0.5f; // matches the cutout block models
    private static final float ENTITY_COVERAGE_CUTOFF = 0.1f;  // discard only near-fully-transparent texels
    // Faint baseline extinction added to every translucent surface regardless of its colour, so perfectly
    // clear glass/ice still dims a shadow ray a little instead of being invisible to it (real glass isn't
    // a perfect transmitter either). Additive with the colour-derived extinction, not a floor — a tinted
    // pane still absorbs its own colour on top of this.
    private static final float TRANSLUCENT_NEUTRAL_EXTINCTION = 0.15f;
    private static final int WHITE_SHADOW_TINT = 0x00FFFFFF;
    // HDR radiance of a full (level-15-equivalent) emitter, modulated by albedo. Baked into every
    // emissive RtMaterialDesc.emissionStrength at compile time (compileDesc/compileEntityDesc), times
    // any resource-pack absolute emission.strength_cd_m2 override; see header() and RtMaterialOverrides.
    //
    // Photometric: cd/m² of the emitting surface, per {@link dev.comfyfluffy.caustica.rt.RtSceneUnits}.
    //
    // Anchored on LUMINOUS EXITANCE, not on flame luminance: a full-strength emitter face radiates about
    // 1,000 lm/m², so one 1 m² block face is a ~1,000 lm lamp — a 75 W-equivalent bulb, which is what a
    // glowstone block is meant to be in a room. Lambertian exitance M = π·L, so L = 1000/π = 318 cd/m².
    //
    // A flame really is far brighter per unit area than a glowstone block, so one baseline cannot be
    // right for both; the mask supplies coverage, not intensity. Exitance is the correct thing to anchor
    // because it is what the emitter contributes to the room, and it happens to land a torch's small
    // emissive footprint near 40 lm—a candle to a small torch.
    public static float defaultEmissionLuminanceCdM2() {
        return RtLookPackage.current().lighting().blockEmissionLuminanceCdM2();
    }
    private static final int EMISSION_STRENGTH_SHIFT = 8;
    private static final int EMISSION_STRENGTH_MASK = 65535;
    // Ceiling of the 16-bit fixed-point strength field, raised with the baseline above. HALF_MAX is the
    // real transport ceiling downstream — Payload.emissionSss is a half2 lane and Light.le is packed
    // R11G11B10 — so clamping here rather than higher keeps the encoded value representable end to end.
    // The quantisation step is MAX/65535 ≈ 1 cd/m², i.e. 0.007% at the baseline. A resource pack's
    // maximum 5x multiplier would reach 75,000 and clamps to this: a 0.19 EV reduction on something
    // already several EV past display white, so invisible.
    private static final float MAX_EMISSION_STRENGTH = 65504.0f;
    private static final int MAX_LOD_SHIFT = 24;

    private static final int MODEL_VARIANTS = 2; // ordinary opaque/cutout and transparent dielectric
    private static final int EMISSION_VARIANTS = 2; // state-gated emission disabled/enabled
    private static final int VARIANT_OPAQUE = 0;
    private static final int VARIANT_GLASS = 1;
    // Profiles a sprite variant can actually be resolved with: RtMaterials.profile() never returns
    // WATER/LAVA (fluids use the dedicated singleton headers), so compiling those variants per sprite
    // would only bloat the table. The variant index math assumes these are the first enum ordinals.
    private static final RtMaterials.Profile[] SPRITE_PROFILES = {
            RtMaterials.Profile.DEFAULT, RtMaterials.Profile.METAL,
            RtMaterials.Profile.GLASS, RtMaterials.Profile.SMOOTH};

    static {
        for (int i = 0; i < SPRITE_PROFILES.length; i++) {
            if (SPRITE_PROFILES[i].ordinal() != i) {
                throw new IllegalStateException("Sprite profile ordinals must be contiguous from 0");
            }
        }
    }

    private volatile Snapshot snapshot;
    private GpuBuffer bindingTable;
    private GpuBuffer surfaceTable;
    private long nextEpoch;
    private Map<Identifier, Integer> entityTextureIds = Map.of();
    private Map<Identifier, EntityTemplate> entityTemplates = Map.of();
    private final Map<EntitySpriteKey, Integer> entitySpriteIds = new HashMap<>();
    // Host mirror of the uploaded binding table, and its content index. Every binding — compiled or
    // appended — is interned here, so deriving a variant twice by different routes yields one ID.
    private final List<MaterialBindingData> bindingRecords = new ArrayList<>();
    private final Map<MaterialBindingData, Integer> bindingIds = new HashMap<>();
    private int entityFallbackId;
    private int bindingCapacity;
    private int nextSurfaceId;
    private int surfaceCapacity;

    private record EntityTemplate(RtMaterialDesc desc, RtBlockMaterials.Entry entry) {
    }

    /** Stable within one atlas epoch even if capture presents a different sprite wrapper instance. */
    private record EntitySpriteKey(Identifier name, Identifier atlas) {
        static EntitySpriteKey of(TextureAtlasSprite sprite) {
            return new EntitySpriteKey(sprite.contents().name(), sprite.atlasLocation());
        }
    }

    private RtMaterialRegistry() {
    }

    /** Build and atomically publish the block and entity registry for the current resource epoch. */
    public void rebuild(GpuContext ctx, RtBlockMaterials blockMaterials, RtMaterialOverrides overrides) {
        Map<TextureAtlasSprite, RtBlockMaterials.Entry> entriesBySprite = blockMaterials.preparedEntries();
        List<TextureAtlasSprite> sprites = new ArrayList<>(entriesBySprite.keySet());
        sprites.sort(Comparator.comparing(sprite -> sprite.contents().name().toString()));
        Map<Identifier, RtBlockMaterials.Entry> entriesByResource = blockMaterials.preparedResourceEntries();
        List<Identifier> entityResources = new ArrayList<>(entriesByResource.keySet());
        entityResources.sort(Comparator.comparing(Identifier::toString));
        RtBlockMaterials.Entry fallbackEntry = blockMaterials.entry(null);

        // One pass per sprite computes both the raw average (the compiled shadow transmittance) and the
        // premultiplied-linear uniform emission summary; sprites are independent, so scan in parallel.
        Map<TextureAtlasSprite, SpriteStats> spriteStats = new ConcurrentHashMap<>();
        sprites.parallelStream().forEach(sprite -> spriteStats.put(sprite, computeSpriteStats(sprite)));

        int profileVariants = SPRITE_PROFILES.length * MODEL_VARIANTS * EMISSION_VARIANTS;
        CompiledTables tables = new CompiledTables(3 + profileVariants + sprites.size() * profileVariants);
        tables.add(compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.DEFAULT, false, true,
                RtMaterialDesc.EmissionSummary.NONE), transparentWhiteAverage(), fallbackEntry, null,
                TERRAIN_COVERAGE_CUTOFF);
        int[] fallbackVariants = new int[profileVariants];
        for (RtMaterials.Profile profile : SPRITE_PROFILES) {
            for (boolean glass : new boolean[]{false, true}) {
                for (boolean emitting : new boolean[]{false, true}) {
                    int variant = index(profile, glass, emitting);
                    if (profile == RtMaterials.Profile.DEFAULT && !glass && !emitting) {
                        fallbackVariants[variant] = 0;
                        continue;
                    }
                    fallbackVariants[variant] = tables.add(
                            compileDesc(glass ? MODEL_DIELECTRIC : MODEL_OPAQUE, 0, profile, emitting, true,
                                    RtMaterialDesc.EmissionSummary.NONE),
                            transparentWhiteAverage(), fallbackEntry, null, TERRAIN_COVERAGE_CUTOFF);
                }
            }
        }
        int waterId = tables.add(compileDesc(MODEL_WATER, 0, RtMaterials.Profile.WATER, false, true,
                RtMaterialDesc.EmissionSummary.NONE), whiteAverage(), fallbackEntry, null,
                TERRAIN_COVERAGE_CUTOFF);
        // Lava's fluid mesher assigns this singleton id (no sprite resolve), so its light color comes from
        // the lava_still albedo grid, producing a mean-color area light.
        int lavaId = tables.add(compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.LAVA, true, true,
                uniformWhiteSummary()), whiteAverage(), fallbackEntry,
                albedoGridFor(sprites, spriteStats, "block/lava_still"), TERRAIN_COVERAGE_CUTOFF);
        int nextEntityFallbackId = tables.add(
                compileEntityDesc(0, true, RtMaterialDesc.EmissionSummary.NONE),
                transparentWhiteAverage(), fallbackEntry, null, ENTITY_COVERAGE_CUTOFF);

        IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        List<MutableCompiledOverride> compiledOverrides = new ArrayList<>();
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            compiledOverrides.add(new MutableCompiledOverride(rule));
        }
        for (TextureAtlasSprite sprite : sprites) {
            RtBlockMaterials.Entry entry = entriesBySprite.get(sprite);
            int baseFeatures = entry.features()
                    & (FEATURE_SPEC | FEATURE_NORMAL | FEATURE_HEURISTIC_EMISSION);
            SpriteStats stats = spriteStats.getOrDefault(sprite, SpriteStats.NEUTRAL);

            // The first sprite-wide (block == null) rule owns this sprite for every state, so its variants
            // are compiled straight into the primary map and the base variants are never emitted. Only
            // block-conditional rules stay in the per-quad runtime scan.
            MutableCompiledOverride spriteWide = null;
            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (compiled.rule.block() == null && compiled.rule.matchesSprite(sprite)) {
                    spriteWide = compiled;
                    compiled.matchedSprite = true;
                    break;
                }
            }
            // Resolved once per sprite: IOR is a property of the material, so it costs no extra variants
            // — it varies with the sprite, not with the profile/glass/emitting cross product.
            float dielectricIor = RtDielectrics.iorForSprite(sprite.contents().name());
            int[] variants = new int[profileVariants];
            for (RtMaterials.Profile profile : SPRITE_PROFILES) {
                for (boolean glass : new boolean[]{false, true}) {
                    for (boolean emitting : new boolean[]{false, true}) {
                        int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_HEURISTIC_EMISSION;
                        RtMaterialDesc desc = compileDesc(glass ? MODEL_DIELECTRIC : MODEL_OPAQUE, features,
                                profile, emitting, false,
                                variantSummary(features, emitting, entry, stats.uniformSummary()),
                                dielectricIor);
                        if (spriteWide != null) {
                            desc = spriteWide.rule.apply(desc);
                        }
                        variants[index(profile, glass, emitting)] = tables.add(desc, stats.average(),
                                entry, stats.albedoGrid(), TERRAIN_COVERAGE_CUTOFF);
                    }
                }
            }
            ids.put(sprite, variants);

            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (compiled.rule.block() == null || !compiled.rule.matchesSprite(sprite)) continue;
                int[] overrideVariants = new int[profileVariants];
                for (RtMaterials.Profile profile : SPRITE_PROFILES) {
                    for (boolean glass : new boolean[]{false, true}) {
                        for (boolean emitting : new boolean[]{false, true}) {
                            int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_HEURISTIC_EMISSION;
                            RtMaterialDesc base = compileDesc(glass ? MODEL_DIELECTRIC : MODEL_OPAQUE,
                                    features, profile, emitting, false,
                                    variantSummary(features, emitting, entry, stats.uniformSummary()),
                                    dielectricIor);
                            RtMaterialDesc desc = compiled.rule.apply(base);
                            overrideVariants[index(profile, glass, emitting)] = tables.add(desc,
                                    stats.average(), entry, stats.albedoGrid(), TERRAIN_COVERAGE_CUTOFF);
                        }
                    }
                }
                compiled.ids.put(sprite, overrideVariants);
            }
        }

        Map<Identifier, Integer> nextEntityTextureIds = new HashMap<>();
        Map<Identifier, EntityTemplate> nextEntityTemplates = new HashMap<>();
        Set<RtMaterialOverrides.Rule> entityMatchedOverrides = new HashSet<>();
        for (Identifier name : entityResources) {
            RtBlockMaterials.Entry entry = entriesByResource.get(name);
            int features = entry.features() & (FEATURE_SPEC | FEATURE_NORMAL);
            RtMaterialDesc desc = compileEntityDesc(features, false, entry.emissionSummary());
            for (RtMaterialOverrides.Rule rule : overrides.rules()) {
                if (!rule.matchesEntity(name)) continue;
                desc = rule.apply(desc);
                entityMatchedOverrides.add(rule);
                break;
            }
            int id = tables.add(desc, transparentWhiteAverage(), entry, null, ENTITY_COVERAGE_CUTOFF);
            nextEntityTextureIds.put(name, id);
            nextEntityTemplates.put(name, new EntityTemplate(desc, entry));
        }

        // Full entity textures have fixed [0,1] UVs and receive IDs above. Atlas sprites need a second,
        // append-only surface with the actual stitched atlas rectangle, which is only known when the
        // sprite first appears in capture — one per block-entity atlas sprite.
        int surfaceCount = tables.surfaces.size();
        int nextSurfaceCapacity = Math.addExact(surfaceCount, Math.max(64, sprites.size()));
        // Bindings are appended per (surface, albedo slot, coverage mode) actually submitted: an atlas
        // sprite variant, an entity surface reached through a live bindless slot, and the stochastic
        // coverage variant of either. Sixteen bytes each, so the reserve is generous rather than tight.
        int bindingCount = tables.bindings.size();
        int nextBindingCapacity = Math.addExact(bindingCount, Math.max(1024,
                Math.addExact(Math.addExact(sprites.size(), Math.multiplyExact(entityResources.size(), 3)),
                        Math.multiplyExact(RtEntityTextures.maxTextures(), 2))));
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
            if (!compiled.ids.isEmpty() || compiled.matchedSprite
                    || entityMatchedOverrides.contains(compiled.rule)) {
                matchedOverrideRules++;
            } else {
                CausticaMod.LOGGER.warn("RT material override {} matched no compiled texture ({})",
                        compiled.rule.source(), compiled.rule.sprite());
            }
        }
        Snapshot next = new Snapshot(epoch, Collections.unmodifiableMap(ids), fallbackVariants, waterId, lavaId,
                List.copyOf(descriptions), Collections.unmodifiableList(new ArrayList<>(grids)), frozenOverrides);
        entityTextureIds = Collections.unmodifiableMap(nextEntityTextureIds);
        entityTemplates = Collections.unmodifiableMap(nextEntityTemplates);
        entitySpriteIds.clear();
        bindingRecords.clear();
        bindingRecords.addAll(tables.bindings);
        bindingIds.clear();
        for (int i = 0; i < bindingRecords.size(); i++) {
            bindingIds.put(bindingRecords.get(i), i);
        }
        entityFallbackId = nextEntityFallbackId;
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
                == RtMaterialDesc.EmissionSource.HEURISTIC_MASK).count();
        long authoredEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.LAB_PBR).count();
        long uniformEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.STATE_UNIFORM).count();
        double averageCoverage = descriptions.stream().filter(desc -> desc.emissionSummary().emissive())
                .mapToDouble(desc -> desc.emissionSummary().coverage()).average().orElse(0.0);
        CausticaMod.LOGGER.info("RT materials: epoch={}, surfaces={}/{}, bindings={}/{}, blockSprites={}, entityResources={}, overrideRules={}, matchedOverrides={}, emissive={}, labPbrEmission={}, heuristicMasks={}, uniformEmission={}, avgEmissionCoverage={}, tableKiB={}",
                epoch, surfaceCount, nextSurfaceCapacity, bindingCount, nextBindingCapacity,
                sprites.size(), entityResources.size(), overrides.rules().size(),
                matchedOverrideRules, emissive,
                authoredEmission, inferred, uniformEmission,
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

    /** Neutral canonical material used by runtime-only textures (player skins, generated glyph atlases, etc.). */
    public int entityFallbackId() {
        return entityFallbackId;
    }

    public int entityFallbackId(boolean stochasticCoverage) {
        return stochasticCoverage ? withStochasticCoverage(entityFallbackId) : entityFallbackId;
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
                        bindingFlags(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1()));
    }

    /**
     * The binding pairing {@code bindingId}'s surface with bindless albedo slot {@code albedoSlot}. Entity
     * render types share a handful of canonical surfaces but each resolve their own texture, so the slot
     * cannot live on the surface record.
     */
    public synchronized int withAlbedoSlot(int bindingId, int albedoSlot) {
        MaterialBindingData base = bindingRecords.get(bindingId);
        return intern(new MaterialBindingData(
                packBinding0(albedoSlot, bindingCoverage(base.packed0()), bindingFlags(base.packed0())),
                base.surface(), base.shadowTint(), base.packed1()));
    }

    /** Resolve a full entity texture resource to its pack-compiled binding ID. */
    public int resolveEntityTexture(Identifier textureLocation, boolean stochasticCoverage) {
        int id = textureLocation != null
                ? entityTextureIds.getOrDefault(RtBlockMaterials.logicalTextureName(textureLocation), entityFallbackId)
                : entityFallbackId;
        return stochasticCoverage ? withStochasticCoverage(id) : id;
    }

    /**
     * Resolve a block-entity atlas sprite. Canonical texels were compiled at pack load; only this surface's
     * atlas-to-local UV transform is appended now. Existing IDs and page contents are never modified.
     */
    public synchronized int resolveEntitySprite(TextureAtlasSprite sprite, boolean stochasticCoverage) {
        if (sprite == null) return entityFallbackId(stochasticCoverage);
        EntitySpriteKey key = EntitySpriteKey.of(sprite);
        Integer current = entitySpriteIds.get(key);
        if (current != null) return stochasticCoverage ? withStochasticCoverage(current) : current;
        EntityTemplate template = entityTemplates.get(key.name());
        if (template == null) return entityFallbackId(stochasticCoverage);
        if (nextSurfaceId >= surfaceCapacity) {
            throw new IllegalStateException("RT surface material reserve exhausted");
        }
        int surfaceId = nextSurfaceId++;
        SurfaceMaterialData surface = surface(template.desc(), template.entry(),
                sprite.getU0(), sprite.getV0(),
                RtBlockMaterials.inverseExtent(sprite.getU1() - sprite.getU0()),
                RtBlockMaterials.inverseExtent(sprite.getV1() - sprite.getV0()));
        long offset = Math.multiplyExact((long) surfaceId, SurfaceMaterialData.BYTE_SIZE);
        surface.write(MemoryUtil.memByteBuffer(surfaceTable.mapped + offset, SurfaceMaterialData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder()));
        surfaceTable.flush(offset, SurfaceMaterialData.BYTE_SIZE);
        int id = intern(binding(surfaceId, template.desc(), transparentWhiteAverage(),
                BLOCK_ATLAS_ALBEDO_SLOT, ENTITY_COVERAGE_CUTOFF));
        entitySpriteIds.put(key, id);
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
        entityTextureIds = Map.of();
        entityTemplates = Map.of();
        entitySpriteIds.clear();
        bindingRecords.clear();
        bindingIds.clear();
        entityFallbackId = 0;
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

    private static int index(RtMaterials.Profile profile, boolean glass, boolean emitting) {
        // WATER/LAVA cannot classify a sprite; map them defensively to DEFAULT instead of overrunning.
        int p = profile.ordinal() < SPRITE_PROFILES.length ? profile.ordinal() : 0;
        return (p * MODEL_VARIANTS + (glass ? VARIANT_GLASS : VARIANT_OPAQUE))
                * EMISSION_VARIANTS + (emitting ? 1 : 0);
    }

    /** LabPBR/heuristic masks own the summary; otherwise an emitting state falls back to whole-sprite. */
    private static RtMaterialDesc.EmissionSummary variantSummary(int features, boolean emitting,
                                                                 RtBlockMaterials.Entry entry,
                                                                 RtMaterialDesc.EmissionSummary uniformSummary) {
        if ((features & (FEATURE_SPEC | FEATURE_HEURISTIC_EMISSION)) != 0) return entry.emissionSummary();
        return emitting ? uniformSummary : RtMaterialDesc.EmissionSummary.NONE;
    }

    private static RtMaterialDesc compileDesc(int model, int features, RtMaterials.Profile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary) {
        return compileDesc(model, features, profile, emitting, neutral, emissionSummary,
                RtDielectrics.GLASS_IOR);
    }

    private static RtMaterialDesc compileDesc(int model, int features, RtMaterials.Profile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary,
                                              float dielectricIor) {
        float roughness = model == MODEL_DIELECTRIC ? 0.0025f : profile.roughness(); // linear; s = 0.95
        float metalness = model == MODEL_DIELECTRIC ? 0.0f : profile.metalness();
        // Refractive index is per material, not per model: ice and window glass are both
        // MODEL_DIELECTRIC but bend light by measurably different amounts.
        float ior = switch (model) {
            case MODEL_WATER -> RtDielectrics.WATER_IOR;
            case MODEL_DIELECTRIC -> dielectricIor;
            default -> 1.0f;
        };
        float transmission = model == MODEL_WATER || model == MODEL_DIELECTRIC ? 1.0f : 0.0f;
        boolean labPbr = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (labPbr ? RtMaterialDesc.Source.LAB_PBR : RtMaterialDesc.Source.HEURISTIC);
        RtMaterialDesc.EmissionSource emissionSource;
        if ((features & FEATURE_SPEC) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.LAB_PBR;
        } else if ((features & FEATURE_HEURISTIC_EMISSION) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.HEURISTIC_MASK;
        } else if (emitting) {
            emissionSource = RtMaterialDesc.EmissionSource.STATE_UNIFORM;
        } else {
            emissionSource = RtMaterialDesc.EmissionSource.NONE;
        }
        float emissionStrength = emissionSource == RtMaterialDesc.EmissionSource.NONE
                ? 0.0f : defaultEmissionLuminanceCdM2();
        return new RtMaterialDesc(model, source, features, roughness, metalness, ior, transmission,
                emissionSource, emissionStrength, emissionSummary);
    }

    private static RtMaterialDesc compileEntityDesc(int features, boolean neutral,
                                                    RtMaterialDesc.EmissionSummary emissionSummary) {
        boolean authored = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (authored ? RtMaterialDesc.Source.LAB_PBR : RtMaterialDesc.Source.HEURISTIC);
        RtMaterialDesc.EmissionSource emissionSource = (features & FEATURE_SPEC) != 0
                ? RtMaterialDesc.EmissionSource.LAB_PBR : RtMaterialDesc.EmissionSource.NONE;
        float emissionStrength = emissionSource == RtMaterialDesc.EmissionSource.NONE
                ? 0.0f : defaultEmissionLuminanceCdM2();
        return new RtMaterialDesc(MODEL_OPAQUE, source, features, RtMaterials.ENTITY_ROUGH, 0.0f,
                1.0f, 0.0f, emissionSource, emissionStrength, emissionSummary);
    }

    /**
     * The compiled tables under construction during a rebuild. Every compiled surface gets exactly one
     * binding, so the returned binding ID is what geometry stores and what {@link Snapshot} indexes its
     * descriptions and emission grids by.
     */
    private static final class CompiledTables {
        final List<SurfaceMaterialData> surfaces;
        final List<MaterialBindingData> bindings;
        final List<RtMaterialDesc> descriptions;
        final List<RtEmissionGrid> grids;

        CompiledTables(int expected) {
            surfaces = new ArrayList<>(expected);
            bindings = new ArrayList<>(expected);
            descriptions = new ArrayList<>(expected);
            grids = new ArrayList<>(expected);
        }

        int add(RtMaterialDesc desc, float[] average, RtBlockMaterials.Entry entry,
                RtEmissionGrid uniformGrid, float coverageCutoff) {
            int surfaceId = surfaces.size();
            surfaces.add(surface(desc, entry, entry.albedoU(), entry.albedoV(),
                    entry.albedoInvDu(), entry.albedoInvDv()));
            int id = bindings.size();
            bindings.add(binding(surfaceId, desc, average, BLOCK_ATLAS_ALBEDO_SLOT, coverageCutoff));
            descriptions.add(desc);
            grids.add(gridFor(desc, entry, uniformGrid));
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
            case LAB_PBR, HEURISTIC_MASK -> entry.emissionGrid();
            case STATE_UNIFORM -> uniformGrid;
            case NONE -> null;
        };
    }

    /** The whole-sprite albedo grid of a named block sprite (uniform-emitter light color), or null. */
    private static RtEmissionGrid albedoGridFor(List<TextureAtlasSprite> sprites,
                                                Map<TextureAtlasSprite, SpriteStats> spriteStats,
                                                String name) {
        Identifier id = Identifier.withDefaultNamespace(name);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite.contents().name().equals(id)) {
                SpriteStats stats = spriteStats.get(sprite);
                return stats != null ? stats.albedoGrid() : null;
            }
        }
        return null;
    }

    private static SurfaceMaterialData surface(RtMaterialDesc desc, RtBlockMaterials.Entry entry,
                                               float albedoU, float albedoV,
                                               float albedoInvDu, float albedoInvDv) {
        int packedFeatures = desc.features() | (entry.maxLod() << MAX_LOD_SHIFT);
        // Packed unconditionally (0 for non-emissive materials): the shader multiplies surface.emission
        // by this every time, regardless of source, so the package baseline needs no shader copy.
        int strength = Math.round(Math.min(MAX_EMISSION_STRENGTH, desc.emissionStrength())
                * (EMISSION_STRENGTH_MASK / MAX_EMISSION_STRENGTH));
        packedFeatures |= strength << EMISSION_STRENGTH_SHIFT;
        return new SurfaceMaterialData(desc.model(), packedFeatures, entry.pageIndex(),
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(albedoU, albedoV, albedoInvDu, albedoInvDv),
                new Float4(desc.roughness(), desc.metalness(), desc.ior(), desc.transmission()));
    }

    /**
     * The traversal binding a compiled surface gets. Coverage comes from how the surface occupies its
     * footprint and transmittance from whether light crosses it; the two are independent, so neither is
     * derived from the other. Slot 0 is the block atlas — entity render types pair the same surface with
     * their own slot through {@link #withAlbedoSlot}.
     */
    private static MaterialBindingData binding(int surfaceId, RtMaterialDesc desc, float[] average,
                                               int albedoSlot, float coverageCutoff) {
        int coverage = COVERAGE_CUTOUT;
        int flags = 0;
        int shadowTint = WHITE_SHADOW_TINT;
        switch (desc.model()) {
            case MODEL_DIELECTRIC -> {
                // Glass covers its whole footprint — the see-through part of the sprite is clear glass,
                // not absence — so a shadow ray samples no texel and takes the compiled colour.
                coverage = COVERAGE_OPAQUE;
                flags = BINDING_TRANSMISSIVE;
                shadowTint = translucentShadowTint(average);
            }
            // A water surface passes light through uniformly; its colour is the per-primitive biome tint,
            // which multiplies base colour and so flows through transmittance without a second meaning.
            case MODEL_WATER -> {
                coverage = COVERAGE_OPAQUE;
                flags = BINDING_TRANSMISSIVE | BINDING_RECORD_CROSSING;
            }
            default -> {
            }
        }
        return new MaterialBindingData(packBinding0(albedoSlot, coverage, flags), surfaceId, shadowTint,
                packCoverageCutoff(coverageCutoff));
    }

    static int packBinding0(int albedoSlot, int coverageMode, int flags) {
        return (albedoSlot & ALBEDO_SLOT_MASK) | ((coverageMode & COVERAGE_MASK) << COVERAGE_SHIFT)
                | ((flags & FLAGS_MASK) << FLAGS_SHIFT);
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
     * derived from how dark the whole-sprite average is, scaled by its average alpha (how much of the
     * sprite is colorant versus see-through frame), so saturated panes darken transmitted light
     * non-linearly. The neutral term is NOT alpha-scaled — vanilla clear glass has a low natural alpha,
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

    /**
     * Per-sprite compile inputs gathered in a single pixel pass: the raw average RGBA (matching the
     * previous translucent shadow-filter input) and the premultiplied-linear uniform emission summary
     * used when a state emits light but no per-texel mask was compiled.
     */
    private record SpriteStats(float[] average, RtMaterialDesc.EmissionSummary uniformSummary,
                               RtEmissionGrid albedoGrid) {
        static final SpriteStats NEUTRAL = new SpriteStats(transparentWhiteAverage(),
                RtMaterialDesc.EmissionSummary.NONE, null);
    }

    private static SpriteStats computeSpriteStats(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        NativeImage image = ((SpriteContentsAccessor) contents).caustica$originalImage();
        if (image == null || width <= 0 || height <= 0) return SpriteStats.NEUTRAL;
        long sr = 0L, sg = 0L, sb = 0L, sa = 0L;
        double lr = 0.0, lg = 0.0, lb = 0.0;
        int covered = 0;
        // A uniform (state-gated) emitter radiates alpha-weighted albedo per texel; its grid mirrors that
        // so the light collector's footprint math is one code path across all emission sources.
        RtEmissionGrid.Builder gridBuilder = new RtEmissionGrid.Builder(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, y); // frame 0 always occupies the image's top-left tile
                int a = ARGB.alpha(pixel);
                sr += ARGB.red(pixel);
                sg += ARGB.green(pixel);
                sb += ARGB.blue(pixel);
                sa += a;
                float alpha = a / 255.0f;
                float plr = RtMaterialTextureData.srgbToLinear(ARGB.red(pixel)) * alpha;
                float plg = RtMaterialTextureData.srgbToLinear(ARGB.green(pixel)) * alpha;
                float plb = RtMaterialTextureData.srgbToLinear(ARGB.blue(pixel)) * alpha;
                lr += plr;
                lg += plg;
                lb += plb;
                gridBuilder.add(x, y, plr, plg, plb, alpha);
                if (a > 1) covered++;
            }
        }
        float inv = 1.0f / (width * (float) height);
        float scale = inv / 255.0f;
        float[] average = {sr * scale, sg * scale, sb * scale, sa * scale};
        double luminance = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
        RtMaterialDesc.EmissionSummary uniform = luminance <= 0.0
                ? RtMaterialDesc.EmissionSummary.NONE
                : new RtMaterialDesc.EmissionSummary((float) (lr * inv), (float) (lg * inv),
                        (float) (lb * inv), (float) (luminance * inv), covered * inv);
        return new SpriteStats(average, uniform, gridBuilder.build());
    }

    private static final class MutableCompiledOverride {
        final RtMaterialOverrides.Rule rule;
        final IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        // Sprite-wide rules compile into the primary ids map instead of `ids`; this marks them matched.
        boolean matchedSprite;

        MutableCompiledOverride(RtMaterialOverrides.Rule rule) {
            this.rule = rule;
        }

        CompiledOverride freeze() {
            return new CompiledOverride(rule, Collections.unmodifiableMap(new IdentityHashMap<>(ids)));
        }
    }

    private record CompiledOverride(RtMaterialOverrides.Rule rule, Map<TextureAtlasSprite, int[]> ids) {
    }

    /** Read-only lookup captured once by a terrain task. */
    public static final class Snapshot {
        private final long epoch;
        private final Map<TextureAtlasSprite, int[]> ids;
        private final int[] fallbackVariants;
        private final int waterId;
        private final int lavaId;
        private final List<RtMaterialDesc> descriptions;
        private final List<RtEmissionGrid> grids;
        private final List<CompiledOverride> overrides;

        private Snapshot(long epoch, Map<TextureAtlasSprite, int[]> ids, int[] fallbackVariants,
                         int waterId, int lavaId, List<RtMaterialDesc> descriptions,
                         List<RtEmissionGrid> grids, List<CompiledOverride> overrides) {
            this.epoch = epoch;
            this.ids = ids;
            this.fallbackVariants = fallbackVariants;
            this.waterId = waterId;
            this.lavaId = lavaId;
            this.descriptions = descriptions;
            this.grids = grids;
            this.overrides = overrides;
        }

        public long epoch() {
            return epoch;
        }

        public int waterId() {
            return waterId;
        }

        public int lavaId() {
            return lavaId;
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

        public int resolve(TextureAtlasSprite sprite, BlockState state, boolean glass) {
            RtMaterials.Profile profile = RtMaterials.profile(state);
            boolean emitting = state != null && state.getLightEmission() > 0;
            int variant = index(profile, glass, emitting);
            // Only block-conditional rules remain here; sprite-wide overrides were compiled into `ids`.
            for (CompiledOverride override : overrides) {
                if (!override.rule.matches(sprite, state)) continue;
                int[] variants = override.ids.get(sprite);
                if (variants != null) return variants[variant];
            }
            int[] variants = ids.get(sprite);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }

        /** Stateless resolve (entity/block-atlas geometry). Sprite-wide overrides are already folded in. */
        public int resolve(TextureAtlasSprite sprite, RtMaterials.Profile profile, boolean glass, boolean emitting) {
            int[] variants = ids.get(sprite);
            int variant = index(profile, glass, emitting);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }
    }
}
