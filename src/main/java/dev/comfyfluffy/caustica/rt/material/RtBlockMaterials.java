package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.engine.material.MaterialUv;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
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
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;

    private final Map<ResourceId, Entry> entries = new HashMap<>();
    private final List<Page> pages = new ArrayList<>();
    private Entry fallback;
    private boolean loggedFailure;

    private RtBlockMaterials() {
    }

    /** Immutable texture-to-page mapping and compile-time image statistics. */
    public record Entry(int features, int pageIndex, int maxLod,
                        float materialU, float materialV, float materialDu, float materialDv,
                        float albedoU, float albedoV, float albedoInvDu, float albedoInvDv,
                        RtMaterialDesc.EmissionSummary emissionSummary, RtEmissionGrid emissionGrid,
                        float averageR, float averageG, float averageB, float averageA,
                        RtMaterialDesc.EmissionSummary uniformEmissionSummary,
                        RtEmissionGrid albedoGrid) {
        public float[] average() {
            return new float[]{averageR, averageG, averageB, averageA};
        }
    }

    private record Page(RtMaterialPageTexture surface0, RtMaterialPageTexture normal,
                        RtMaterialPageTexture surface1, int index) {
        void destroy() {
            surface0.destroy();
            normal.destroy();
            surface1.destroy();
        }
    }

    record AlbedoStats(float averageR, float averageG, float averageB, float averageA,
                       RtMaterialDesc.EmissionSummary uniformSummary,
                       RtEmissionGrid grid) {
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
        RtEmissionGrid emissionGrid;
        AlbedoStats stats = AlbedoStats.NEUTRAL;

        Candidate(MaterialTextureAsset asset) {
            this.asset = asset;
            int value = 0;
            if (asset.surfaceParameters()) value |= RtMaterialRegistry.FEATURE_SPEC;
            if (asset.normalMap()) value |= RtMaterialRegistry.FEATURE_NORMAL;
            if (asset.emissionMask()) value |= RtMaterialRegistry.FEATURE_EMISSION_MASK;
            features = value;
        }

        int width() {
            return asset.width();
        }

        int height() {
            return asset.height();
        }

        boolean requiresPage() {
            return features != 0 || asset.kind() == MaterialTextureKind.STANDALONE;
        }
    }

    private static final class LayoutPage {
        final int size;
        int x;
        int y;
        int rowHeight;

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
    }

    /** Compile, pack, mip, upload, and publish one immutable host catalog. */
    public void prepareAll(GpuContext ctx, int materialPageCapacity, MaterialCatalog catalog) {
        List<Candidate> candidates = new ArrayList<>(catalog.atlasAssets().size() + catalog.standalone().size());
        catalog.atlasAssets().forEach(asset -> candidates.add(new Candidate(asset)));
        catalog.standalone().forEach(asset -> candidates.add(new Candidate(asset)));
        List<Candidate> paged = candidates.stream().filter(Candidate::requiresPage).collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        int specCount = 0;
        int normalCount = 0;
        int emissionMaskCount = 0;
        int largest = 1 + 2 * GUTTER;
        for (Candidate candidate : paged) {
            if ((candidate.features & RtMaterialRegistry.FEATURE_SPEC) != 0) specCount++;
            if ((candidate.features & RtMaterialRegistry.FEATURE_NORMAL) != 0) normalCount++;
            if ((candidate.features & RtMaterialRegistry.FEATURE_EMISSION_MASK) != 0) emissionMaskCount++;
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
            pagePixels[pageIndex] = new PagePixels(pageSize, mipCount);
        }
        pagePixels[0].writeFallback();
        paged.parallelStream().filter(candidate -> candidate.page >= 0).forEach(candidate -> {
            try {
                Decoded decoded = decode(candidate);
                candidate.emissionSummary = decoded.emissionSummary();
                candidate.emissionGrid = decoded.emissionGrid();
                candidate.stats = decoded.stats();
                pagePixels[candidate.page].write(candidate, decoded.levels());
            } catch (Throwable t) {
                warnOnce("RT canonical material decode failed for " + candidate.asset.material(), t);
                candidate.page = -1;
            }
        });
        candidates.parallelStream().filter(candidate -> candidate.page < 0).forEach(candidate -> {
            try {
                candidate.stats = scanAlbedo(candidate.asset);
            } catch (Throwable t) {
                warnOnce("RT material image scan failed for " + candidate.asset.material(), t);
            }
        });
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            PagePixels pixels = pagePixels[pageIndex];
            pages.add(new Page(
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface0,
                            "material surface0 page " + pageIndex),
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.normal,
                            "material normal page " + pageIndex),
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface1,
                            "material surface1 page " + pageIndex),
                    pageIndex));
        }

        float fallbackUv = GUTTER / (float) pageSize;
        fallback = new Entry(0, 0, 0, fallbackUv, fallbackUv,
                1.0f / pageSize, 1.0f / pageSize, 0, 0, 1, 1,
                RtMaterialDesc.EmissionSummary.NONE, null,
                1.0f, 1.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSummary.NONE, null);
        for (Candidate candidate : candidates) {
            entries.put(candidate.asset.material(), candidate.page >= 0
                    ? compiledEntry(candidate, pageSize) : fallbackFor(candidate.asset.albedoUv(), candidate.stats));
        }

        long bytesPerBundle = 0L;
        int w = pageSize;
        for (int mip = 0; mip < mipCount; mip++) {
            bytesPerBundle += (long) w * w * 4L;
            w = Math.max(1, w / 2);
        }
        CausticaMod.LOGGER.info("RT canonical material pages: atlasAssets={}, standaloneAssets={}, surface={}, normal={}, emissionMasks={}, pages={}, size={}x{}, validLod<={}, gpuMiB={}",
                catalog.atlasAssets().size(), catalog.standalone().size(), specCount, normalCount,
                emissionMaskCount, pages.size(), pageSize, pageSize, MAX_VALID_LOD,
                String.format(java.util.Locale.ROOT, "%.2f", bytesPerBundle * pages.size() * 3.0 / (1024.0 * 1024.0)));
    }

    public void bindPages(RtPipeline pipeline, long sampler) {
        for (Page page : pages) {
            pipeline.setMaterialPage(page.index(), page.surface0().view(), page.normal().view(),
                    page.surface1().view(), sampler);
        }
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
                candidate.emissionGrid, stats.averageR(), stats.averageG(), stats.averageB(), stats.averageA(),
                stats.uniformSummary(), stats.grid());
    }

    private Entry fallbackFor(MaterialUv uv, AlbedoStats stats) {
        return new Entry(0, fallback.pageIndex(), 0,
                fallback.materialU, fallback.materialV, fallback.materialDu, fallback.materialDv,
                uv.u(), uv.v(), uv.inverseDu(), uv.inverseDv(), RtMaterialDesc.EmissionSummary.NONE, null,
                stats.averageR(), stats.averageG(), stats.averageB(), stats.averageA(),
                stats.uniformSummary(), stats.grid());
    }

    private record Decoded(List<RtMaterialTextureData.Level> levels,
                           RtMaterialDesc.EmissionSummary emissionSummary,
                           RtEmissionGrid emissionGrid, AlbedoStats stats) {
    }

    private static RtEmissionGrid emissionGrid(float[] linearAlbedo, float[] mask, int width, int height) {
        RtEmissionGrid.Builder builder = new RtEmissionGrid.Builder(width, height);
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

    private static Decoded decode(Candidate candidate) throws Exception {
        try (MaterialTextureImage texture = candidate.asset.texture().open()) {
            int width = candidate.width();
            int height = candidate.height();
            float[] surface0 = new float[width * height * 4];
            float[] normal = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] linearAlbedo = new float[surface0.length];
            float[] authoredEmission = candidate.asset.emissionMask() ? new float[width * height] : null;
            StatsAccumulator stats = new StatsAccumulator(width, height);
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
            RtEmissionGrid grid = null;
            if (authoredEmission != null) {
                emissionSummary = summarizeEmission(linearAlbedo, authoredEmission);
                grid = emissionGrid(linearAlbedo, authoredEmission, width, height);
            }
            int maxLod = maxLodFor(width, height);
            return new Decoded(RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normal, surface1), maxLod), emissionSummary, grid, stats.finish());
        }
    }

    static AlbedoStats scanAlbedo(MaterialTextureAsset asset) throws Exception {
        try (MaterialTextureImage image = asset.texture().open()) {
            StatsAccumulator stats = new StatsAccumulator(asset.width(), asset.height());
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
        private final RtEmissionGrid.Builder grid;
        private long sr;
        private long sg;
        private long sb;
        private long sa;
        private double lr;
        private double lg;
        private double lb;
        private int covered;

        StatsAccumulator(int width, int height) {
            this.width = width;
            this.height = height;
            grid = new RtEmissionGrid.Builder(width, height);
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
            float pr = RtMaterialTextureData.srgbToLinear(r) * coverage;
            float pg = RtMaterialTextureData.srgbToLinear(g) * coverage;
            float pb = RtMaterialTextureData.srgbToLinear(b) * coverage;
            lr += pr;
            lg += pg;
            lb += pb;
            grid.add(x, y, pr, pg, pb, coverage);
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
            return new AlbedoStats(sr * scale, sg * scale, sb * scale, sa * scale, uniform, grid.build());
        }
    }

    private static final class PagePixels {
        final List<byte[]> surface0;
        final List<byte[]> normal;
        final List<byte[]> surface1;
        final int pageSize;

        PagePixels(int pageSize, int mipCount) {
            this.pageSize = pageSize;
            surface0 = allocate(pageSize, mipCount, 255, 0, 0, 0);
            normal = allocate(pageSize, mipCount, 128, 128, 0, 0);
            surface1 = allocate(pageSize, mipCount, 255, 255, 255,
                    RtMaterialTextureData.unorm8(encodeIor(OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR)));
        }

        void writeFallback() {
        }

        void write(Candidate candidate, List<RtMaterialTextureData.Level> levels) {
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
