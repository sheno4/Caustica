package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.EmissionFootprint;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.engine.material.MaterialUv;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.engine.material.OpenPbrTextureTexel;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiles host-provided OpenPBR texture inputs into canonical GPU pages. */
public final class RtMaterialPageCompiler {
    public static final RtMaterialPageCompiler INSTANCE = new RtMaterialPageCompiler();

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;
    public static final int ALPHA_SOURCE_NONE = 0;
    public static final int ALPHA_SOURCE_STATIC_PAGE = 1;
    public static final int ALPHA_SOURCE_ANIMATED_RANGE = 2;

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

    private RtMaterialPageCompiler() {
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

    record AlbedoStats(float averageR, float averageG, float averageB, float averageA,
                       RtMaterialDesc.EmissionSummary uniformEmissionSummary,
                       EmissionFootprint uniformEmissionFootprint) {
        private static final AlbedoStats NEUTRAL = new AlbedoStats(1.0f, 1.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSummary.NONE, null);
    }

    private static final class Candidate {
        final MaterialTextureAsset asset;
        final int features;
        int page = -1;
        int x;
        int y;
        RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
        EmissionFootprint emissionFootprint;
        AlbedoStats stats = AlbedoStats.NEUTRAL;
        float minAlpha;
        float maxAlpha = 1.0f;
        final int alphaFrameCount;
        TemporalAlpha alphaSamples;
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

        boolean requiresPage() {
            return features != 0 || asset.kind() == MaterialTextureKind.STANDALONE
                    || needsStaticAlpha || needsAlphaRange;
        }
    }

    private static final class LayoutPage {
        final int size;
        int x;
        int y;
        int rowHeight;
        boolean hasPbr;
        boolean hasAlphaRange;
        boolean hasStaticAlpha;

        LayoutPage(int size, boolean reserveFallback) {
            this.size = size;
            if (reserveFallback) y = align(1 + 2 * GUTTER, PACK_ALIGNMENT);
        }

        boolean place(Candidate candidate) {
            int cellWidth = align(candidate.width() + 2 * GUTTER, PACK_ALIGNMENT);
            int cellHeight = align(candidate.height() + 2 * GUTTER, PACK_ALIGNMENT);
            if (cellWidth > size || cellHeight > size) return false;
            if (x + cellWidth > size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + cellHeight > size) return false;
            candidate.x = x + GUTTER;
            candidate.y = y + GUTTER;
            hasPbr |= candidate.features != 0;
            hasAlphaRange |= candidate.needsAlphaRange;
            hasStaticAlpha |= candidate.needsStaticAlpha;
            x += cellWidth;
            rowHeight = Math.max(rowHeight, cellHeight);
            return true;
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
        candidates.parallelStream().forEach(candidate -> prepareAlpha(candidate, footprintResolution));
        List<Candidate> paged = candidates.stream().filter(Candidate::requiresPage).collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        int largest = 1 + 2 * GUTTER;
        for (Candidate candidate : paged) {
            largest = Math.max(largest, Math.max(candidate.width(), candidate.height()) + 2 * GUTTER);
        }

        int pageSize = paged.isEmpty() ? 32 : Math.max(DEFAULT_PAGE_SIZE, nextPowerOfTwo(largest));
        if (pageSize > MAX_PAGE_SIZE) {
            CausticaMod.LOGGER.warn("RT material asset exceeds canonical page limit ({} > {}); oversized maps use neutral fallback",
                    pageSize, MAX_PAGE_SIZE);
            pageSize = MAX_PAGE_SIZE;
            paged.removeIf(candidate -> candidate.width() + 2 * GUTTER > MAX_PAGE_SIZE
                    || candidate.height() + 2 * GUTTER > MAX_PAGE_SIZE);
            if (paged.isEmpty()) pageSize = 32;
        }
        compiledPageSize = pageSize;
        paged.sort(Comparator.<Candidate>comparingInt(Candidate::height).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::width).reversed())
                .thenComparing(candidate -> candidate.asset.material().toString()));

        List<LayoutPage> layouts = new ArrayList<>();
        layouts.add(new LayoutPage(pageSize, true));
        for (Candidate candidate : paged) {
            boolean placed = false;
            for (int i = 0; i < layouts.size(); i++) {
                if (layouts.get(i).place(candidate)) {
                    candidate.page = i;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                LayoutPage page = new LayoutPage(pageSize, false);
                if (!page.place(candidate)) continue;
                candidate.page = layouts.size();
                layouts.add(page);
            }
        }
        if (layouts.size() > materialPageCapacity) {
            throw new IllegalStateException("RT material pages require " + layouts.size()
                    + " descriptor slots but capacity is " + materialPageCapacity);
        }

        int mipCount = Integer.numberOfTrailingZeros(pageSize) + 1;
        PagePixels[] pagePixels = new PagePixels[layouts.size()];
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            LayoutPage layout = layouts.get(pageIndex);
            pagePixels[pageIndex] = new PagePixels(pageSize, mipCount, layout.hasPbr,
                    layout.hasStaticAlpha, layout.hasAlphaRange);
        }
        paged.parallelStream().filter(candidate -> candidate.page >= 0).forEach(candidate -> {
            try {
                if (candidate.features != 0 || candidate.asset.kind() == MaterialTextureKind.STANDALONE) {
                    Decoded decoded = decode(candidate, footprintResolution);
                    candidate.emissionSummary = decoded.emissionSummary();
                    candidate.emissionFootprint = decoded.emissionFootprint();
                    candidate.stats = decoded.stats();
                    candidate.minAlpha = decoded.minAlpha();
                    candidate.maxAlpha = decoded.maxAlpha();
                    pagePixels[candidate.page].write(candidate, decoded.levels());
                }
                if (candidate.needsAlphaRange) {
                    pagePixels[candidate.page].writeTemporalAlpha(candidate, candidate.alphaSamples.texels());
                }
                if (candidate.needsStaticAlpha) {
                    pagePixels[candidate.page].writeStaticAlpha(candidate, candidate.alphaSamples.texels());
                }
            } catch (Throwable t) {
                warnOnce("RT canonical material decode failed for " + candidate.asset.material(), t);
                candidate.page = -1;
            }
        });
        candidates.parallelStream().filter(candidate -> candidate.page < 0 && !candidate.statsPrepared).forEach(candidate -> {
            try {
                candidate.stats = scanAlbedo(candidate.asset, footprintResolution);
            } catch (Throwable t) {
                warnOnce("RT material image scan failed for " + candidate.asset.material(), t);
            }
        });
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            PagePixels pixels = pagePixels[pageIndex];
            pages.add(new Page(
                    pixels.surface0 == null ? null : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface0,
                            "material surface0 page " + pageIndex),
                    pixels.normal == null ? null : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.normal,
                            "material normal page " + pageIndex),
                    pixels.surface1 == null ? null : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface1,
                            "material surface1 page " + pageIndex),
                    pixels.staticAlpha == null ? null : new RtMaterialPageTexture(ctx, pageSize, pageSize,
                            pixels.staticAlpha, "material static alpha page " + pageIndex, true,
                            org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM),
                    pixels.temporalAlpha == null ? null : new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.temporalAlpha,
                            "material temporal alpha page " + pageIndex, true, org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM),
                    pageIndex));
        }
        neutralSurface0 = neutral(ctx, 255, 0, 0, 0, "material neutral surface0", false);
        neutralNormal = neutral(ctx, 128, 128, 0, 0, "material neutral normal", false);
        neutralSurface1 = neutral(ctx, 255, 255, 255,
                RtMaterialTextureData.unorm8(encodeIor(OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR)),
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
        AlbedoStats stats = candidate.stats;
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
        AlbedoStats stats = candidate.stats;
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

    private record Decoded(List<RtMaterialTextureData.Level> levels,
                           RtMaterialDesc.EmissionSummary emissionSummary,
                           EmissionFootprint emissionFootprint, AlbedoStats stats,
                           float minAlpha, float maxAlpha) {
    }

    private void prepareAlpha(Candidate candidate, int footprintResolution) {
        if (candidate.alphaFrameCount <= 0) {
            candidate.alphaSource = ALPHA_SOURCE_NONE;
            return;
        }
        try (MaterialTextureImage texture = candidate.asset.texture().open()) {
            TemporalAlpha temporal = scanTemporalAlpha(texture, candidate.width(), candidate.height());
            candidate.minAlpha = temporal.minAlpha();
            candidate.maxAlpha = temporal.maxAlpha();
            StatsAccumulator stats = new StatsAccumulator(candidate.width(), candidate.height(),
                    footprintResolution, candidate.asset.emissionColorBinding());
            for (int y = 0; y < candidate.height(); y++) {
                for (int x = 0; x < candidate.width(); x++) {
                    stats.add(x, y, sample(texture, x, y, candidate.width(), candidate.height()));
                }
            }
            candidate.stats = stats.finish();
            candidate.statsPrepared = true;
            if (candidate.alphaFrameCount == 1 || !hasTemporalVariation(temporal)) {
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

    static boolean hasTemporalVariation(TemporalAlpha alpha) {
        float[] texels = alpha.texels();
        for (int i = 0; i < texels.length; i += 4) {
            if (texels[i] != texels[i + 1]) return true;
        }
        return false;
    }

    private static EmissionFootprint emissionFootprint(float[] linearAlbedo, float[] mask,
                                                       int width, int height, int resolution) {
        EmissionFootprint.Builder builder = new EmissionFootprint.Builder(resolution, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                float weight = Math.clamp(mask[pixel], 0.0f, 1.0f);
                int i = pixel * 4;
                builder.add(x, y, linearAlbedo[i] * weight, linearAlbedo[i + 1] * weight,
                        linearAlbedo[i + 2] * weight, weight);
            }
        }
        return builder.build();
    }

    private static Decoded decode(Candidate candidate, int footprintResolution) throws Exception {
        try (MaterialTextureImage texture = candidate.asset.texture().open()) {
            int width = candidate.width();
            int height = candidate.height();
            float[] surface0 = new float[width * height * 4];
            float[] normal = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] linearAlbedo = new float[surface0.length];
            float[] authoredEmission = candidate.asset.emissionMask() ? new float[width * height] : null;
            float[] emissionColor = authoredEmission != null ? new float[surface0.length] : null;
            boolean emissionUsesBaseColor = candidate.asset.emissionColorBinding()
                    == OpenPbrColorBinding.BASE_COLOR;
            StatsAccumulator stats = new StatsAccumulator(width, height, footprintResolution,
                    candidate.asset.emissionColorBinding());
            OpenPbrTextureTexel texel = new OpenPbrTextureTexel();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int albedoPixel = sample(texture, x, y, width, height);
                    stats.add(x, y, albedoPixel);
                    float ar = RtMaterialTextureData.srgbToLinear(red(albedoPixel));
                    float ag = RtMaterialTextureData.srgbToLinear(green(albedoPixel));
                    float ab = RtMaterialTextureData.srgbToLinear(blue(albedoPixel));
                    float aa = alpha(albedoPixel) / 255.0f;
                    linearAlbedo[i] = ar;
                    linearAlbedo[i + 1] = ag;
                    linearAlbedo[i + 2] = ab;
                    linearAlbedo[i + 3] = aa;
                    if (emissionColor != null) {
                        emissionColor[i] = emissionUsesBaseColor ? ar : 1.0f;
                        emissionColor[i + 1] = emissionUsesBaseColor ? ag : 1.0f;
                        emissionColor[i + 2] = emissionUsesBaseColor ? ab : 1.0f;
                        emissionColor[i + 3] = aa;
                    }
                    texel.reset();
                    texture.readOpenPbr(x, y, texel);
                    surface0[i] = texel.specularRoughness;
                    surface0[i + 1] = texel.baseMetalness;
                    surface0[i + 2] = texel.emissionWeight;
                    surface0[i + 3] = texel.subsurfaceWeight;
                    normal[i] = texel.tangentNormalX * 0.5f + 0.5f;
                    normal[i + 1] = texel.tangentNormalY * 0.5f + 0.5f;
                    normal[i + 3] = texel.normalHeight;
                    surface1[i] = texel.metalBaseColorR;
                    surface1[i + 1] = texel.metalBaseColorG;
                    surface1[i + 2] = texel.metalBaseColorB;
                    surface1[i + 3] = encodeIor(texel.specularIor);
                    if (authoredEmission != null) authoredEmission[y * width + x] = surface0[i + 2] * aa;
                }
            }
            RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
            EmissionFootprint footprint = null;
            if (authoredEmission != null) {
                emissionSummary = summarizeEmission(emissionColor, authoredEmission);
                footprint = emissionFootprint(emissionColor, authoredEmission, width, height,
                        footprintResolution);
            }
            int maxLod = maxLodFor(width, height);
            return new Decoded(RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normal, surface1), maxLod), emissionSummary, footprint,
                    stats.finish(), candidate.minAlpha, candidate.maxAlpha);
        }
    }

    record TemporalAlpha(float[] texels, float minAlpha, float maxAlpha) { }

    static TemporalAlpha scanTemporalAlpha(MaterialTextureImage texture, int width, int height) {
        if (texture.alphaFrameCount() <= 0) {
            throw new IllegalArgumentException("Material texture has no alpha frames");
        }
        float[] texels = new float[width * height * 4];
        int materialMin = 255;
        int materialMax = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int min = 255;
                int max = 0;
                for (int frame = 0; frame < texture.alphaFrameCount(); frame++) {
                    int value = alpha(texture.alphaArgb(frame, x, y));
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                int index = (y * width + x) * 4;
                texels[index] = min / 255.0f;
                texels[index + 1] = max / 255.0f;
                materialMin = Math.min(materialMin, min);
                materialMax = Math.max(materialMax, max);
            }
        }
        return new TemporalAlpha(texels, materialMin / 255.0f, materialMax / 255.0f);
    }

    static AlbedoStats scanAlbedo(MaterialTextureAsset asset, int footprintResolution) throws Exception {
        try (MaterialTextureImage image = asset.texture().open()) {
            StatsAccumulator stats = new StatsAccumulator(asset.width(), asset.height(), footprintResolution,
                    asset.emissionColorBinding());
            for (int y = 0; y < asset.height(); y++) {
                for (int x = 0; x < asset.width(); x++) {
                    stats.add(x, y, sample(image, x, y, asset.width(), asset.height()));
                }
            }
            return stats.finish();
        }
    }

    private static final class StatsAccumulator {
        private final int width;
        private final int height;
        private final EmissionFootprint.Builder footprint;
        private final OpenPbrColorBinding emissionColor;
        private long sr;
        private long sg;
        private long sb;
        private long sa;
        private double lr;
        private double lg;
        private double lb;
        private int covered;

        StatsAccumulator(int width, int height, int footprintResolution,
                         OpenPbrColorBinding emissionColor) {
            this.width = width;
            this.height = height;
            this.emissionColor = emissionColor;
            footprint = new EmissionFootprint.Builder(footprintResolution, width, height);
        }

        void add(int x, int y, int pixel) {
            int a = alpha(pixel);
            int r = red(pixel);
            int g = green(pixel);
            int b = blue(pixel);
            sr += r;
            sg += g;
            sb += b;
            sa += a;
            float coverage = a / 255.0f;
            float pr = (emissionColor == OpenPbrColorBinding.BASE_COLOR
                    ? RtMaterialTextureData.srgbToLinear(r) : 1.0f) * coverage;
            float pg = (emissionColor == OpenPbrColorBinding.BASE_COLOR
                    ? RtMaterialTextureData.srgbToLinear(g) : 1.0f) * coverage;
            float pb = (emissionColor == OpenPbrColorBinding.BASE_COLOR
                    ? RtMaterialTextureData.srgbToLinear(b) : 1.0f) * coverage;
            lr += pr;
            lg += pg;
            lb += pb;
            footprint.add(x, y, pr, pg, pb, coverage);
            if (a > 1) covered++;
        }

        AlbedoStats finish() {
            float inv = 1.0f / (width * (float) height);
            float scale = inv / 255.0f;
            double luminance = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
            RtMaterialDesc.EmissionSummary uniform = luminance <= 0.0
                    ? RtMaterialDesc.EmissionSummary.NONE
                    : new RtMaterialDesc.EmissionSummary((float) (lr * inv), (float) (lg * inv),
                    (float) (lb * inv), (float) (luminance * inv), covered * inv);
            return new AlbedoStats(sr * scale, sg * scale, sb * scale, sa * scale, uniform,
                    footprint.build());
        }
    }

    private static final class PagePixels {
        final List<byte[]> surface0;
        final List<byte[]> normal;
        final List<byte[]> surface1;
        final List<byte[]> staticAlpha;
        final List<byte[]> temporalAlpha;
        final int pageSize;

        PagePixels(int pageSize, int mipCount, boolean pbr, boolean staticAlphaPresent, boolean alphaRange) {
            this.pageSize = pageSize;
            surface0 = pbr ? allocate(pageSize, mipCount, 255, 0, 0, 0) : null;
            normal = pbr ? allocate(pageSize, mipCount, 128, 128, 0, 0) : null;
            surface1 = pbr ? allocate(pageSize, mipCount, 255, 255, 255,
                    RtMaterialTextureData.unorm8(encodeIor(OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR)))
                    : null;
            staticAlpha = staticAlphaPresent ? allocateR(pageSize, 0) : null;
            temporalAlpha = alphaRange ? allocateRg(pageSize, 1, 0, 255) : null;
        }

        void write(Candidate candidate, List<RtMaterialTextureData.Level> levels) {
            if (surface0 == null) return;
            for (int mip = 0; mip < levels.size(); mip++) {
                RtMaterialTextureData.Level level = levels.get(mip);
                int width = Math.max(1, pageSize >> mip);
                int cx = candidate.x >> mip;
                int cy = candidate.y >> mip;
                int gutter = Math.max(1, GUTTER >> mip);
                blit(surface0.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.surface0());
                blit(normal.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.normal());
                blit(surface1.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.surface1());
            }
        }

        void writeTemporalAlpha(Candidate candidate, float[] values) {
            blitRg(temporalAlpha.get(0), pageSize, candidate.x, candidate.y, GUTTER,
                    candidate.width(), candidate.height(), values);
        }

        void writeStaticAlpha(Candidate candidate, float[] values) {
            blitR(staticAlpha.get(0), pageSize, candidate.x, candidate.y, GUTTER,
                    candidate.width(), candidate.height(), values);
        }

        private static List<byte[]> allocateR(int size, int value) {
            byte[] values = new byte[size * size];
            java.util.Arrays.fill(values, (byte) value);
            return List.of(values);
        }

        private static void blitR(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                                  int srcWidth, int srcHeight, float[] src) {
            for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
                int sy = Math.clamp(dy, 0, srcHeight - 1);
                int ty = cy + dy;
                if (ty < 0 || ty >= dstWidth) continue;
                for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                    int sx = Math.clamp(dx, 0, srcWidth - 1);
                    int tx = cx + dx;
                    if (tx < 0 || tx >= dstWidth) continue;
                    dst[ty * dstWidth + tx] = (byte) RtMaterialTextureData.unorm8(
                            src[(sy * srcWidth + sx) * 4]);
                }
            }
        }

        private static List<byte[]> allocateRg(int size, int mipCount, int r, int g) {
            List<byte[]> result = new ArrayList<>(mipCount);
            int width = size;
            for (int mip = 0; mip < mipCount; mip++) {
                byte[] values = new byte[width * width * 2];
                for (int i = 0; i < values.length; i += 2) {
                    values[i] = (byte) r;
                    values[i + 1] = (byte) g;
                }
                result.add(values);
                width = Math.max(1, width / 2);
            }
            return result;
        }

        private static void blitRg(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                                   int srcWidth, int srcHeight, float[] src) {
            for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
                int sy = Math.clamp(dy, 0, srcHeight - 1);
                int ty = cy + dy;
                if (ty < 0 || ty >= dstWidth) continue;
                for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                    int sx = Math.clamp(dx, 0, srcWidth - 1);
                    int tx = cx + dx;
                    if (tx < 0 || tx >= dstWidth) continue;
                    int si = (sy * srcWidth + sx) * 4;
                    int di = (ty * dstWidth + tx) * 2;
                    dst[di] = (byte) RtMaterialTextureData.unorm8(src[si]);
                    dst[di + 1] = (byte) RtMaterialTextureData.unorm8(src[si + 1]);
                }
            }
        }

        private static List<byte[]> allocate(int size, int mipCount, int r, int g, int b, int a) {
            List<byte[]> result = new ArrayList<>(mipCount);
            int width = size;
            for (int mip = 0; mip < mipCount; mip++) {
                byte[] values = new byte[width * width * 4];
                for (int i = 0; i < values.length; i += 4) {
                    values[i] = (byte) r;
                    values[i + 1] = (byte) g;
                    values[i + 2] = (byte) b;
                    values[i + 3] = (byte) a;
                }
                result.add(values);
                width = Math.max(1, width / 2);
            }
            return result;
        }

        private static void blit(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                                 int srcWidth, int srcHeight, float[] src) {
            for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
                int sy = Math.clamp(dy, 0, srcHeight - 1);
                int ty = cy + dy;
                if (ty < 0 || ty >= dstWidth) continue;
                for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                    int sx = Math.clamp(dx, 0, srcWidth - 1);
                    int tx = cx + dx;
                    if (tx < 0 || tx >= dstWidth) continue;
                    int si = (sy * srcWidth + sx) * 4;
                    int di = (ty * dstWidth + tx) * 4;
                    dst[di] = (byte) RtMaterialTextureData.unorm8(src[si]);
                    dst[di + 1] = (byte) RtMaterialTextureData.unorm8(src[si + 1]);
                    dst[di + 2] = (byte) RtMaterialTextureData.unorm8(src[si + 2]);
                    dst[di + 3] = (byte) RtMaterialTextureData.unorm8(src[si + 3]);
                }
            }
        }
    }

    static int maxLodFor(int width, int height) {
        return Math.min(MAX_VALID_LOD, 31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
    }

    private static int sample(MaterialTextureImage image, int x, int y, int width, int height) {
        int sx = Math.min(image.width() - 1, x * image.width() / width);
        int sy = Math.min(image.height() - 1, y * image.height() / height);
        return image.albedoArgb(sx, sy);
    }

    private static RtMaterialDesc.EmissionSummary summarizeEmission(float[] rgba, float[] mask) {
        double r = 0, g = 0, b = 0, energy = 0;
        int covered = 0;
        for (int pixel = 0; pixel < mask.length; pixel++) {
            int i = pixel * 4;
            float weight = Math.clamp(mask[pixel], 0.0f, 1.0f);
            float er = rgba[i] * weight, eg = rgba[i + 1] * weight, eb = rgba[i + 2] * weight;
            r += er; g += eg; b += eb;
            energy += 0.2126 * er + 0.7152 * eg + 0.0722 * eb;
            if (weight > 1.0f / 255.0f) covered++;
        }
        if (energy <= 0.0) return RtMaterialDesc.EmissionSummary.NONE;
        float inv = 1.0f / mask.length;
        return new RtMaterialDesc.EmissionSummary((float) r * inv, (float) g * inv, (float) b * inv,
                (float) energy * inv, covered * inv);
    }

    private static float encodeIor(float ior) {
        return Math.clamp((ior - 1.0f) / (ior + 1.0f), 0.0f, 254.0f / 255.0f);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static int red(int argb) {
        return argb >>> 16 & 255;
    }

    private static int green(int argb) {
        return argb >>> 8 & 255;
    }

    private static int blue(int argb) {
        return argb & 255;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private synchronized void warnOnce(String message, Throwable throwable) {
        if (!loggedFailure) {
            loggedFailure = true;
            CausticaMod.LOGGER.warn(message, throwable);
        }
    }
}
