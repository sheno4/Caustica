package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureData;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.api.provider.TextureRegistrar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compiles Minecraft OpenPBR texture inputs into provider-owned canonical texture pages and material blobs. */
public final class MinecraftMaterialPageCompiler {
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    public static final int FEATURE_EMISSION_MASK = 4;
    public static final int FEATURE_SUBSURFACE_COLOR_BASE = 8;
    public static final int FEATURE_EMISSION_COLOR_BASE = 16;

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int MAX_SLOT = 0xFFFF;
    private static final int MAX_LOD_SHIFT = 24;
    private static final int FEATURE_MASK = 0x00FFFFFF;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;
    private static final int MATERIAL_TEXTURE_FEATURES = FEATURE_SPEC | FEATURE_NORMAL | FEATURE_EMISSION_MASK;

    private MinecraftMaterialPageCompiler() {
    }

    /** Provider data and CPU-visible feature bits for one material. */
    public record CompiledMaterial(MaterialProviderData providerData, int features) {
        public CompiledMaterial {
            java.util.Objects.requireNonNull(providerData, "providerData");
        }
    }

    /** Immutable compiled records keyed by material identifier, plus the textureless fallback record. */
    public record Result(Map<ResourceId, CompiledMaterial> materials, CompiledMaterial fallback) {
        public Result {
            materials = Map.copyOf(materials);
            java.util.Objects.requireNonNull(fallback, "fallback");
        }

        public CompiledMaterial material(ResourceId id) {
            return materials.getOrDefault(id, fallback);
        }
    }

    private static final class Candidate {
        final MaterialTextureResource resource;
        final int features;
        int page = -1;
        int x;
        int y;

        Candidate(MaterialTextureResource resource) {
            this.resource = resource;
            int value = 0;
            if (resource.surfaceParameters()) value |= FEATURE_SPEC;
            if (resource.normalMap()) value |= FEATURE_NORMAL;
            if (resource.emissionMask()) value |= FEATURE_EMISSION_MASK;
            if (resource.subsurfaceColorBinding() == OpenPbrColorBinding.BASE_COLOR) {
                value |= FEATURE_SUBSURFACE_COLOR_BASE;
            }
            if (resource.emissionColorBinding() == OpenPbrColorBinding.BASE_COLOR) {
                value |= FEATURE_EMISSION_COLOR_BASE;
            }
            features = value;
        }

        int width() { return resource.analysisSource().width(); }
        int height() { return resource.analysisSource().height(); }
        int pageChannels() { return MinecraftMaterialPageCompiler.pageChannels(features); }
    }

    private record PageSlots(int surface0, int normal, int surface1, int emission) {
    }

    private record PageUv(float u, float v, float du, float dv) {
        private static final PageUv IDENTITY = new PageUv(0.0f, 0.0f, 1.0f, 1.0f);
    }

    /** Compile and register every texture before material definitions that contain the returned slots are submitted. */
    public static Result compile(List<MaterialTextureResource> resources, TextureRegistrar textures) {
        return compile(resources, textures, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, GUTTER);
    }

    static Result compile(List<MaterialTextureResource> resources, TextureRegistrar textures,
                          int defaultPageSize, int maxPageSize, int gutter) {
        java.util.Objects.requireNonNull(resources, "resources");
        java.util.Objects.requireNonNull(textures, "textures");
        List<MaterialTextureResource> ordered = new ArrayList<>(List.copyOf(resources));
        ordered.sort(Comparator.comparing(MaterialTextureResource::material));
        Set<ResourceId> ids = new java.util.HashSet<>();
        for (MaterialTextureResource resource : ordered) {
            if (!ids.add(resource.material())) {
                throw new IllegalArgumentException("duplicate material texture resource " + resource.material());
            }
        }

        PageSlots neutral = new PageSlots(
                register(textures, neutral(255, 0, 0, 0)),
                register(textures, neutral(128, 128, 0, 0)),
                register(textures, neutral(255, 255, 255,
                        MaterialTextureData.unorm8(MaterialPagePacker.encodeIor(
                                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR)))),
                register(textures, neutral(255, 255, 255, 255)));

        List<Candidate> candidates = ordered.stream().map(Candidate::new).toList();
        List<MinecraftMaterialPagePlanner.Input> inputs = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            inputs.add(new MinecraftMaterialPagePlanner.Input(index, candidate.resource.material().toString(),
                    candidate.width(), candidate.height(), candidate.pageChannels()));
        }
        MinecraftMaterialPagePlanner.Plan plan = MinecraftMaterialPagePlanner.plan(inputs, defaultPageSize,
                maxPageSize, gutter, PACK_ALIGNMENT);
        if (plan.rejectedOversizedInput()) {
            CausticaMod.LOGGER.warn("RT material asset exceeds canonical page limit {}; oversized maps use neutral fallback",
                    maxPageSize);
        }
        int pageSize = plan.pageSize();
        for (MinecraftMaterialPagePlanner.Placement placement : plan.placements()) {
            Candidate candidate = candidates.get(placement.inputIndex());
            candidate.page = placement.pageIndex();
            candidate.x = placement.x();
            candidate.y = placement.y();
        }

        int mipCount = Integer.numberOfTrailingZeros(pageSize) + 1;
        MaterialPagePacker[] pixels = new MaterialPagePacker[plan.layouts().size()];
        for (int page = 0; page < plan.layouts().size(); page++) {
            MinecraftMaterialPagePlanner.Layout layout = plan.layouts().get(page);
            pixels[page] = new MaterialPagePacker(pageSize, mipCount, gutter,
                    layout.has(MinecraftMaterialPagePlanner.CHANNEL_MATERIAL),
                    layout.has(MinecraftMaterialPagePlanner.CHANNEL_EMISSION));
        }
        AtomicBoolean loggedFailure = new AtomicBoolean();
        candidates.parallelStream().filter(candidate -> candidate.page >= 0).forEach(candidate -> {
            try {
                MaterialTextureAnalyzer.Decoded decoded = MaterialTextureAnalyzer.decode(
                        candidate.resource.analysisSource(), candidate.resource.emissionColorBinding(),
                        maxLodFor(candidate.width(), candidate.height()));
                pixels[candidate.page].write(candidate.x, candidate.y, decoded.levels());
            } catch (Throwable failure) {
                if (loggedFailure.compareAndSet(false, true)) {
                    CausticaMod.LOGGER.warn("RT canonical material decode failed for "
                            + candidate.resource.material(), failure);
                }
                candidate.page = -1;
            }
        });

        List<PageSlots> pages = new ArrayList<>(pixels.length);
        for (MaterialPagePacker page : pixels) {
            pages.add(new PageSlots(
                    page.surface0 == null ? neutral.surface0() : register(textures, texture(pageSize, page.surface0)),
                    page.normal == null ? neutral.normal() : register(textures, texture(pageSize, page.normal)),
                    page.surface1 == null ? neutral.surface1() : register(textures, texture(pageSize, page.surface1)),
                    page.emission == null ? neutral.emission() : register(textures, texture(pageSize, page.emission))));
        }

        CompiledMaterial fallback = compiled(0, 0, neutral, PageUv.IDENTITY, MaterialUv.IDENTITY);
        Map<ResourceId, CompiledMaterial> compiled = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            CompiledMaterial material;
            if (candidate.page >= 0) {
                PageUv materialUv = new PageUv(candidate.x / (float) pageSize,
                        candidate.y / (float) pageSize, candidate.width() / (float) pageSize,
                        candidate.height() / (float) pageSize);
                material = compiled(candidate.features, maxLodFor(candidate.width(), candidate.height()),
                        pages.get(candidate.page), materialUv, candidate.resource.albedoUv());
            } else {
                int features = candidate.features
                        & (FEATURE_SUBSURFACE_COLOR_BASE | FEATURE_EMISSION_COLOR_BASE);
                material = compiled(features, 0, neutral, PageUv.IDENTITY, candidate.resource.albedoUv());
            }
            compiled.put(candidate.resource.material(), material);
        }
        return new Result(compiled, fallback);
    }

    static int pageChannels(int features) {
        int channels = (features & MATERIAL_TEXTURE_FEATURES) != 0
                ? MinecraftMaterialPagePlanner.CHANNEL_MATERIAL : 0;
        if ((features & FEATURE_EMISSION_MASK) != 0) {
            channels |= MinecraftMaterialPagePlanner.CHANNEL_EMISSION;
        }
        return channels;
    }

    static boolean eligibleForPageCompilation(MaterialTextureResource resource) {
        return MinecraftMaterialPagePlanner.eligible(resource.analysisSource().width(),
                resource.analysisSource().height(), MAX_PAGE_SIZE, GUTTER);
    }

    static int maxLodFor(int width, int height) {
        return Math.min(MAX_VALID_LOD, 31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
    }

    private static CompiledMaterial compiled(int features, int maxLod, PageSlots slots,
                                             PageUv materialUv, MaterialUv baseColorUv) {
        int[] words = new int[MaterialProviderData.WORD_COUNT];
        words[0] = (features & FEATURE_MASK) | (maxLod << MAX_LOD_SHIFT);
        words[1] = slots.surface0() | slots.surface1() << 16;
        words[2] = slots.normal() | slots.emission() << 16;
        words[3] = Float.floatToRawIntBits(materialUv.u());
        words[4] = Float.floatToRawIntBits(materialUv.v());
        words[5] = Float.floatToRawIntBits(materialUv.du());
        words[6] = Float.floatToRawIntBits(materialUv.dv());
        putBaseColorUv(words, baseColorUv);
        return new CompiledMaterial(new MaterialProviderData(words), features);
    }

    private static void putBaseColorUv(int[] words, MaterialUv uv) {
        words[7] = Float.floatToRawIntBits(uv.u());
        words[8] = Float.floatToRawIntBits(uv.v());
        words[9] = Float.floatToRawIntBits(uv.inverseDu());
        words[10] = Float.floatToRawIntBits(uv.inverseDv());
    }

    private static int register(TextureRegistrar textures, CpuTextureResource texture) {
        int slot = textures.register(texture);
        if (slot < 0 || slot > MAX_SLOT) {
            throw new IllegalStateException("Minecraft material texture slot exceeds 16-bit provider ABI: " + slot);
        }
        return slot;
    }

    private static CpuTextureResource neutral(int r, int g, int b, int a) {
        return new CpuTextureResource(1, 1, CpuTextureResource.Encoding.LINEAR,
                new byte[] { (byte) r, (byte) g, (byte) b, (byte) a });
    }

    private static CpuTextureResource texture(int baseSize, List<byte[]> levels) {
        List<CpuTextureResource.MipLevel> mipLevels = new ArrayList<>(levels.size());
        int size = baseSize;
        for (byte[] level : levels) {
            mipLevels.add(new CpuTextureResource.MipLevel(size, size, level));
            size = Math.max(1, size / 2);
        }
        return new CpuTextureResource(CpuTextureResource.Encoding.LINEAR, mipLevels);
    }
}
