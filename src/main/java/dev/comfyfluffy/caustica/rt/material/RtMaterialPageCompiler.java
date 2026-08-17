package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialUv;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.engine.material.OpenPbrTextureTexel;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiles host-provided OpenPBR texture inputs into canonical GPU pages. */
public final class RtMaterialPageCompiler {

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;
    public static final int ALPHA_SOURCE_NONE = 0;
    public static final int ALPHA_SOURCE_STATIC_PAGE = 1;
    public static final int ALPHA_SOURCE_ANIMATED_RANGE = 2;
    private static final int MATERIAL_TEXTURE_FEATURES = RtMaterialRegistry.FEATURE_SPEC
            | RtMaterialRegistry.FEATURE_NORMAL | RtMaterialRegistry.FEATURE_EMISSION_MASK;

    private final Map<ResourceId, Entry> entries = new HashMap<>();
    private final List<Page> pages = new ArrayList<>();
    private Entry fallback;
    private int compiledPageSize;
    private boolean loggedFailure;
    private RtMaterialPageTexture neutralSurface0;
    private RtMaterialPageTexture neutralNormal;
    private RtMaterialPageTexture neutralSurface1;
    private RtMaterialPageTexture neutralTemporalAlpha;
    private RtMaterialPageTexture neutralStaticAlpha;

    RtMaterialPageCompiler() {
    }

    /** Immutable texture-to-page mapping and compile-time image statistics. */
    public record Entry(int features, int pageIndex, int maxLod,
                        float materialU, float materialV, float materialDu, float materialDv,
                        float albedoU, float albedoV, float albedoInvDu, float albedoInvDv,
                        RtMaterialDesc.EmissionSummary emissionSummary, EmissionFootprint emissionFootprint,
                        float averageR, float averageG, float averageB, float averageA,
                        float minAlpha, float maxAlpha,
                        int alphaSource,
                        RtMaterialDesc.EmissionSummary uniformEmissionSummary,
                        EmissionFootprint uniformEmissionFootprint) {
        public float[] average() {
            return new float[]{averageR, averageG, averageB, averageA};
        }
    }

    private record Page(RtMaterialPageTexture surface0, RtMaterialPageTexture normal,
                        RtMaterialPageTexture surface1, RtMaterialPageTexture staticAlpha,
                        RtMaterialPageTexture temporalAlpha, int index) {
        void destroy() {
            if (surface0 != null) surface0.destroy();
            if (normal != null) normal.destroy();
            if (surface1 != null) surface1.destroy();
            if (staticAlpha != null) staticAlpha.destroy();
            if (temporalAlpha != null) temporalAlpha.destroy();
        }
    }

    static final class OwnedResources<T> {
        private final List<T> resources = new ArrayList<>();

        T own(T resource) {
            if (resource != null) resources.add(resource);
            return resource;
        }

        void transfer() {
            resources.clear();
        }

        void destroy(java.util.function.Consumer<T> destroyer) {
            for (int i = resources.size() - 1; i >= 0; i--) destroyer.accept(resources.get(i));
            resources.clear();
        }
    }

    private static final class Candidate {
        final MaterialTextureAsset asset;
        final int features;
        int page = -1;
        int x;
        int y;
        RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
        EmissionFootprint emissionFootprint;
        MaterialTextureAnalyzer.AlbedoStats stats = MaterialTextureAnalyzer.AlbedoStats.NEUTRAL;
        float minAlpha;
        float maxAlpha = 1.0f;
        final int alphaFrameCount;
        MaterialTextureAnalyzer.Alpha alphaSamples;
        boolean needsAlphaRange;
        boolean needsStaticAlpha;
        int alphaSource;
        boolean statsPrepared;

        Candidate(MaterialTextureAsset asset) {
            this.asset = asset;
            int value = 0;
            if (asset.surfaceParameters()) value |= RtMaterialRegistry.FEATURE_SPEC;
            if (asset.normalMap()) value |= RtMaterialRegistry.FEATURE_NORMAL;
            if (asset.emissionMask()) value |= RtMaterialRegistry.FEATURE_EMISSION_MASK;
            if (asset.subsurfaceColorBinding() == OpenPbrColorBinding.BASE_COLOR) {
                value |= RtMaterialRegistry.FEATURE_SUBSURFACE_COLOR_BASE;
            }
            if (asset.emissionColorBinding() == OpenPbrColorBinding.BASE_COLOR) {
                value |= RtMaterialRegistry.FEATURE_EMISSION_COLOR_BASE;
            }
            features = value;
            alphaFrameCount = asset.texture().alphaFrameCount();
        }

        int width() {
            return asset.width();
        }

        int height() {
            return asset.height();
        }

        int pageChannels() {
            return RtMaterialPageCompiler.pageChannels(features, needsStaticAlpha, needsAlphaRange);
        }
    }

    /** Drop the previous epoch's CPU mappings and GPU pages. Caller owns the idle boundary. */
    public void reset() {
        entries.clear();
        fallback = null;
        for (Page page : pages) page.destroy();
        pages.clear();
        if (neutralSurface0 != null) neutralSurface0.destroy();
        if (neutralNormal != null) neutralNormal.destroy();
        if (neutralSurface1 != null) neutralSurface1.destroy();
        if (neutralTemporalAlpha != null) neutralTemporalAlpha.destroy();
        if (neutralStaticAlpha != null) neutralStaticAlpha.destroy();
        neutralSurface0 = neutralNormal = neutralSurface1 = neutralTemporalAlpha = neutralStaticAlpha = null;
        compiledPageSize = 0;
    }

    /** Compile, pack, mip, upload, and publish one immutable host catalog. */
    public void prepareAll(GpuContext ctx, int materialPageCapacity, MaterialCatalog catalog) {
        int footprintResolution = catalog.emissionFootprintResolution();
        List<Candidate> candidates = new ArrayList<>(catalog.atlasAssets().size() + catalog.standalone().size());
        catalog.atlasAssets().forEach(asset -> candidates.add(new Candidate(asset)));
        catalog.standalone().forEach(asset -> candidates.add(new Candidate(asset)));
        candidates.parallelStream().filter(candidate -> eligibleForPageCompilation(candidate.asset))
                .forEach(candidate -> prepareAlpha(candidate, footprintResolution));
        List<RtMaterialPagePlanner.Input> inputs = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            inputs.add(new RtMaterialPagePlanner.Input(i, candidate.asset.material().toString(),
                    candidate.width(), candidate.height(), candidate.pageChannels()));
        }
        RtMaterialPagePlanner.Plan plan = RtMaterialPagePlanner.plan(inputs, DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE, GUTTER, PACK_ALIGNMENT);
        if (plan.rejectedOversizedInput()) {
            CausticaMod.LOGGER.warn("RT material asset exceeds canonical page limit {}; oversized maps use neutral fallback",
                    MAX_PAGE_SIZE);
        }
        int pageSize = plan.pageSize();
        compiledPageSize = pageSize;
        for (RtMaterialPagePlanner.Placement placement : plan.placements()) {
            Candidate candidate = candidates.get(placement.inputIndex());
            candidate.page = placement.pageIndex();
            candidate.x = placement.x();
            candidate.y = placement.y();
        }
        if (plan.layouts().size() > materialPageCapacity) {
            throw new IllegalStateException("RT material pages require " + plan.layouts().size()
                    + " descriptor slots but capacity is " + materialPageCapacity);
        }

        int mipCount = Integer.numberOfTrailingZeros(pageSize) + 1;
        MaterialPagePacker[] pagePixels = new MaterialPagePacker[plan.layouts().size()];
        for (int pageIndex = 0; pageIndex < plan.layouts().size(); pageIndex++) {
            RtMaterialPagePlanner.Layout layout = plan.layouts().get(pageIndex);
            pagePixels[pageIndex] = new MaterialPagePacker(pageSize, mipCount, GUTTER,
                    layout.has(RtMaterialPagePlanner.CHANNEL_MATERIAL),
                    layout.has(RtMaterialPagePlanner.CHANNEL_STATIC_ALPHA),
                    layout.has(RtMaterialPagePlanner.CHANNEL_TEMPORAL_ALPHA));
        }
        List<Candidate> paged = candidates.stream().filter(candidate -> candidate.page >= 0).toList();
        paged.parallelStream().forEach(candidate -> {
            try {
                if ((candidate.features & MATERIAL_TEXTURE_FEATURES) != 0) {
                    MaterialTextureAnalyzer.Decoded decoded = MaterialTextureAnalyzer.decode(candidate.asset,
                            footprintResolution, maxLodFor(candidate.width(), candidate.height()));
                    candidate.emissionSummary = decoded.emissionSummary();
                    candidate.emissionFootprint = decoded.emissionFootprint();
                    candidate.stats = decoded.stats();
                    pagePixels[candidate.page].write(candidate.x, candidate.y, decoded.levels());
                }
                if (candidate.needsAlphaRange) {
                    pagePixels[candidate.page].writeTemporalAlpha(candidate.x, candidate.y,
                            candidate.width(), candidate.height(), candidate.alphaSamples.texels());
                }
                if (candidate.needsStaticAlpha) {
                    pagePixels[candidate.page].writeStaticAlpha(candidate.x, candidate.y,
                            candidate.width(), candidate.height(), candidate.alphaSamples.texels());
                }
            } catch (Throwable t) {
                warnOnce("RT canonical material decode failed for " + candidate.asset.material(), t);
                candidate.page = -1;
            }
        });
        candidates.parallelStream().filter(candidate -> candidate.page < 0 && !candidate.statsPrepared
                && eligibleForPageCompilation(candidate.asset)).forEach(candidate -> {
            try {
                candidate.stats = MaterialTextureAnalyzer.scanAlbedo(candidate.asset, footprintResolution);
            } catch (Throwable t) {
                warnOnce("RT material image scan failed for " + candidate.asset.material(), t);
            }
        });
        for (int pageIndex = 0; pageIndex < plan.layouts().size(); pageIndex++) {
            MaterialPagePacker pixels = pagePixels[pageIndex];
            OwnedResources<RtMaterialPageTexture> owned = new OwnedResources<>();
            try {
                RtMaterialPageTexture surface0 = owned.own(pixels.surface0 == null ? null
                        : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface0,
                        "material surface0 page " + pageIndex));
                RtMaterialPageTexture normal = owned.own(pixels.normal == null ? null
                        : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.normal,
                        "material normal page " + pageIndex));
                RtMaterialPageTexture surface1 = owned.own(pixels.surface1 == null ? null
                        : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface1,
                        "material surface1 page " + pageIndex));
                RtMaterialPageTexture staticAlpha = owned.own(pixels.staticAlpha == null ? null
                        : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.staticAlpha,
                        "material static alpha page " + pageIndex, true,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM));
                RtMaterialPageTexture temporalAlpha = owned.own(pixels.temporalAlpha == null ? null
                        : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.temporalAlpha,
                        "material temporal alpha page " + pageIndex, true,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM));
                pages.add(new Page(surface0, normal, surface1, staticAlpha, temporalAlpha, pageIndex));
                owned.transfer();
            } catch (Throwable failure) {
                owned.destroy(RtMaterialPageTexture::destroy);
                throw failure;
            }
        }
        neutralSurface0 = neutral(ctx, 255, 0, 0, 0, "material neutral surface0", false);
        neutralNormal = neutral(ctx, 128, 128, 0, 0, "material neutral normal", false);
        neutralSurface1 = neutral(ctx, 255, 255, 255,
                RtMaterialTextureData.unorm8(MaterialPagePacker.encodeIor(OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR)),
                "material neutral surface1", false);
        neutralTemporalAlpha = new RtMaterialPageTexture(ctx, 1, 1,
                List.of(new byte[]{0, (byte) 255}), "material neutral temporal alpha", true,
                org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM);
        neutralStaticAlpha = new RtMaterialPageTexture(ctx, 1, 1, List.of(new byte[]{0}),
                "material neutral static alpha", true, org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM);

        float fallbackUv = GUTTER / (float) pageSize;
        fallback = new Entry(0, 0, 0, fallbackUv, fallbackUv,
                1.0f / pageSize, 1.0f / pageSize, 0, 0, 1, 1,
                RtMaterialDesc.EmissionSummary.NONE, null,
                1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                ALPHA_SOURCE_NONE,
                RtMaterialDesc.EmissionSummary.NONE, null);
        for (Candidate candidate : candidates) {
            entries.put(candidate.asset.material(), candidate.page >= 0
                    ? compiledEntry(candidate, pageSize) : fallbackFor(candidate));
        }
    }

    public void bindPages(RtPipeline pipeline, long sampler) {
        for (Page page : pages) {
            pipeline.setMaterialPage(page.index(), view(page.surface0(), neutralSurface0),
                    view(page.normal(), neutralNormal), view(page.surface1(), neutralSurface1), sampler);
        }
    }

    public long[] temporalAlphaViews() {
        long[] views = new long[pages.size()];
        for (Page page : pages) views[page.index()] = view(page.temporalAlpha(), neutralTemporalAlpha);
        return views;
    }

    public long[] staticAlphaViews() {
        long[] views = new long[pages.size()];
        for (Page page : pages) views[page.index()] = view(page.staticAlpha(), neutralStaticAlpha);
        return views;
    }

    private static long view(RtMaterialPageTexture texture, RtMaterialPageTexture fallback) {
        return (texture != null ? texture : fallback).view();
    }

    private static RtMaterialPageTexture neutral(GpuContext ctx, int r, int g, int b, int a,
                                                  String label, boolean asyncShared) {
        return new RtMaterialPageTexture(ctx, 1, 1, List.of(new byte[]{(byte) r, (byte) g, (byte) b, (byte) a}),
                label, asyncShared);
    }

    static int pageChannels(int features, boolean staticAlpha, boolean temporalAlpha) {
        int channels = (features & MATERIAL_TEXTURE_FEATURES) != 0
                ? RtMaterialPagePlanner.CHANNEL_MATERIAL : 0;
        if (staticAlpha) channels |= RtMaterialPagePlanner.CHANNEL_STATIC_ALPHA;
        if (temporalAlpha) channels |= RtMaterialPagePlanner.CHANNEL_TEMPORAL_ALPHA;
        return channels;
    }

    static boolean eligibleForPageCompilation(MaterialTextureAsset asset) {
        return RtMaterialPagePlanner.eligible(asset.width(), asset.height(), MAX_PAGE_SIZE, GUTTER);
    }

    public int pageSize() {
        return compiledPageSize;
    }

    public Entry entry(ResourceId material) {
        return material == null ? fallback : entries.getOrDefault(material, fallback);
    }

    public Map<ResourceId, Entry> preparedEntries() {
        return Collections.unmodifiableMap(new HashMap<>(entries));
    }

    public void destroy() {
        reset();
    }

    private Entry compiledEntry(Candidate candidate, int pageSize) {
        MaterialUv uv = candidate.asset.albedoUv();
        MaterialTextureAnalyzer.AlbedoStats stats = candidate.stats;
        return new Entry(candidate.features, candidate.page, maxLodFor(candidate.width(), candidate.height()),
                candidate.x / (float) pageSize, candidate.y / (float) pageSize,
                candidate.width() / (float) pageSize, candidate.height() / (float) pageSize,
                uv.u(), uv.v(), uv.inverseDu(), uv.inverseDv(), candidate.emissionSummary,
                candidate.emissionFootprint, stats.averageR(), stats.averageG(), stats.averageB(), stats.averageA(),
                candidate.minAlpha, candidate.maxAlpha,
                publishedAlphaSource(candidate.alphaSource,
                        candidate.needsStaticAlpha || candidate.needsAlphaRange, true),
                stats.uniformEmissionSummary(), stats.uniformEmissionFootprint());
    }

    private Entry fallbackFor(Candidate candidate) {
        MaterialUv uv = candidate.asset.albedoUv();
        MaterialTextureAnalyzer.AlbedoStats stats = candidate.stats;
        int colorBindings = candidate.features & (RtMaterialRegistry.FEATURE_SUBSURFACE_COLOR_BASE
                | RtMaterialRegistry.FEATURE_EMISSION_COLOR_BASE);
        return new Entry(colorBindings, fallback.pageIndex(), 0,
                fallback.materialU, fallback.materialV, fallback.materialDu, fallback.materialDv,
                uv.u(), uv.v(), uv.inverseDu(), uv.inverseDv(), RtMaterialDesc.EmissionSummary.NONE, null,
                stats.averageR(), stats.averageG(), stats.averageB(), stats.averageA(),
                candidate.minAlpha, candidate.maxAlpha,
                publishedAlphaSource(candidate.alphaSource,
                        candidate.needsStaticAlpha || candidate.needsAlphaRange, false),
                stats.uniformEmissionSummary(), stats.uniformEmissionFootprint());
    }

    private void prepareAlpha(Candidate candidate, int footprintResolution) {
        if (candidate.alphaFrameCount <= 0) {
            candidate.alphaSource = ALPHA_SOURCE_NONE;
            return;
        }
        try {
            MaterialTextureAnalyzer.Alpha temporal = MaterialTextureAnalyzer.scanAlpha(candidate.asset);
            candidate.minAlpha = temporal.minAlpha();
            candidate.maxAlpha = temporal.maxAlpha();
            candidate.stats = MaterialTextureAnalyzer.scanAlbedo(candidate.asset, footprintResolution);
            candidate.statsPrepared = true;
            if (candidate.alphaFrameCount == 1 || !MaterialTextureAnalyzer.hasTemporalVariation(temporal)) {
                candidate.alphaSource = ALPHA_SOURCE_STATIC_PAGE;
                candidate.needsStaticAlpha = requiresSpatialAlpha(candidate.minAlpha, candidate.maxAlpha);
                if (candidate.needsStaticAlpha) candidate.alphaSamples = temporal;
            } else {
                candidate.alphaSource = alphaSource(candidate.alphaFrameCount);
                candidate.needsAlphaRange = requiresTemporalRange(candidate.alphaFrameCount,
                        candidate.minAlpha, candidate.maxAlpha);
                if (candidate.needsAlphaRange) candidate.alphaSamples = temporal;
            }
        } catch (Throwable failure) {
            candidate.alphaSource = ALPHA_SOURCE_NONE;
            warnOnce("RT material alpha scan failed for " + candidate.asset.material(), failure);
        }
    }

    static int alphaSource(int frameCount) {
        if (frameCount == 1) {
            return ALPHA_SOURCE_STATIC_PAGE;
        }
        return frameCount > 1 ? ALPHA_SOURCE_ANIMATED_RANGE : ALPHA_SOURCE_NONE;
    }

    static boolean requiresTemporalRange(int frameCount, float minAlpha, float maxAlpha) {
        return frameCount > 1 && minAlpha < maxAlpha;
    }

    static boolean requiresSpatialAlpha(float minAlpha, float maxAlpha) {
        return minAlpha < maxAlpha;
    }

    static int publishedAlphaSource(int alphaSource, boolean requiresSpatialPage,
                                    boolean spatialPagePresent) {
        return requiresSpatialPage && !spatialPagePresent ? ALPHA_SOURCE_NONE : alphaSource;
    }

    static int maxLodFor(int width, int height) {
        return Math.min(MAX_VALID_LOD, 31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
    }

    private synchronized void warnOnce(String message, Throwable throwable) {
        if (!loggedFailure) {
            loggedFailure = true;
            CausticaMod.LOGGER.warn(message, throwable);
        }
    }
}
