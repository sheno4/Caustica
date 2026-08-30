package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compiles Minecraft OpenPBR inputs into immutable CPU texture pages for one resource-pack epoch. */
public final class MinecraftMaterialPageCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftMaterialPageCompiler.class);
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    public static final int FEATURE_EMISSION_MASK = 4;
    public static final int FEATURE_SUBSURFACE_COLOR_BASE = 8;
    public static final int FEATURE_EMISSION_COLOR_BASE = 16;
    public static final int FEATURE_UNIFORM_EMISSION = 32;
    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;
    private static final int MATERIAL_TEXTURE_FEATURES = FEATURE_SPEC | FEATURE_NORMAL | FEATURE_EMISSION_MASK;

    private MinecraftMaterialPageCompiler() { }

    /** Texture ordinals and UV transforms used to build a generated GPU material record. */
    public record CompiledMaterial(int features, int maxLod, int surface0Texture,
                                   int surface1Texture, int normalTexture, int emissionTexture,
                                   MaterialUv materialUv, MaterialUv baseColorUv) {
        public CompiledMaterial {
            if (maxLod < 0 || maxLod > MAX_VALID_LOD) throw new IllegalArgumentException("invalid max LOD");
            if (surface0Texture < 0 || surface1Texture < 0 || normalTexture < 0 || emissionTexture < 0) {
                throw new IllegalArgumentException("texture ordinals must be non-negative");
            }
            java.util.Objects.requireNonNull(materialUv, "materialUv");
            java.util.Objects.requireNonNull(baseColorUv, "baseColorUv");
        }
    }

    /** CPU upload batch and its material placements. */
    public record Result(Map<ResourceId, CompiledMaterial> materials, CompiledMaterial fallback,
                         List<MinecraftMaterialTexture> textures) {
        public Result {
            materials = Map.copyOf(materials);
            java.util.Objects.requireNonNull(fallback, "fallback");
            textures = List.copyOf(textures);
        }
        public CompiledMaterial material(ResourceId id) { return materials.getOrDefault(id, fallback); }
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
            if (resource.subsurfaceColorBinding() == OpenPbrColorBinding.BASE_COLOR) value |= FEATURE_SUBSURFACE_COLOR_BASE;
            if (resource.emissionColorBinding() == OpenPbrColorBinding.BASE_COLOR) value |= FEATURE_EMISSION_COLOR_BASE;
            features = value;
        }
        int width() { return resource.analysisSource().width(); }
        int height() { return resource.analysisSource().height(); }
        int pageChannels() { return MinecraftMaterialPageCompiler.pageChannels(features); }
    }

    private record PageSlots(int surface0, int normal, int surface1, int emission) { }

    public static Result compile(List<MaterialTextureResource> resources) {
        return compile(resources, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, GUTTER);
    }

    static Result compile(List<MaterialTextureResource> resources, int defaultPageSize, int maxPageSize, int gutter) {
        List<MaterialTextureResource> ordered = new ArrayList<>(List.copyOf(resources));
        ordered.sort(Comparator.comparing(MaterialTextureResource::material));
        Set<ResourceId> ids = new java.util.HashSet<>();
        for (MaterialTextureResource resource : ordered) {
            if (!ids.add(resource.material())) throw new IllegalArgumentException("duplicate material " + resource.material());
        }

        List<MinecraftMaterialTexture> textures = new ArrayList<>();
        PageSlots neutral = new PageSlots(
                add(textures, neutral(255, 0, 0, 0)), add(textures, neutral(128, 128, 0, 0)),
                add(textures, neutral(255, 255, 255,
                        MaterialTextureLevels.unorm8(MaterialPagePacker.encodeIor(OpenPbrDefaults.SPECULAR_IOR)))),
                add(textures, neutral(255, 255, 255, 255)));

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
            LOGGER.warn("RT material asset exceeds canonical page limit {}; using neutral fallback", maxPageSize);
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
                    LOGGER.warn("RT canonical material decode failed for " + candidate.resource.material(), failure);
                }
                candidate.page = -1;
            }
        });

        List<PageSlots> pages = new ArrayList<>(pixels.length);
        for (MaterialPagePacker page : pixels) {
            pages.add(new PageSlots(
                    page.surface0 == null ? neutral.surface0() : add(textures, texture(pageSize, page.surface0)),
                    page.normal == null ? neutral.normal() : add(textures, texture(pageSize, page.normal)),
                    page.surface1 == null ? neutral.surface1() : add(textures, texture(pageSize, page.surface1)),
                    page.emission == null ? neutral.emission() : add(textures, texture(pageSize, page.emission))));
        }

        CompiledMaterial fallback = compiled(0, 0, neutral, MaterialUv.IDENTITY, MaterialUv.IDENTITY);
        Map<ResourceId, CompiledMaterial> compiled = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            CompiledMaterial material;
            if (candidate.page >= 0) {
                MaterialUv pageUv = new MaterialUv(candidate.x / (float) pageSize,
                        candidate.y / (float) pageSize, candidate.width() / (float) pageSize,
                        candidate.height() / (float) pageSize);
                material = compiled(candidate.features, maxLodFor(candidate.width(), candidate.height()),
                        pages.get(candidate.page), pageUv, candidate.resource.albedoUv());
            } else {
                int features = candidate.features & (FEATURE_SUBSURFACE_COLOR_BASE | FEATURE_EMISSION_COLOR_BASE);
                material = compiled(features, 0, neutral, MaterialUv.IDENTITY, candidate.resource.albedoUv());
            }
            compiled.put(candidate.resource.material(), material);
        }
        return new Result(compiled, fallback, textures);
    }

    static int pageChannels(int features) {
        int channels = (features & MATERIAL_TEXTURE_FEATURES) != 0 ? MinecraftMaterialPagePlanner.CHANNEL_MATERIAL : 0;
        if ((features & FEATURE_EMISSION_MASK) != 0) channels |= MinecraftMaterialPagePlanner.CHANNEL_EMISSION;
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
                                             MaterialUv materialUv, MaterialUv baseColorUv) {
        return new CompiledMaterial(features, maxLod, slots.surface0(), slots.surface1(), slots.normal(),
                slots.emission(), materialUv, baseColorUv);
    }

    private static int add(List<MinecraftMaterialTexture> textures, MinecraftMaterialTexture texture) {
        int ordinal = textures.size();
        textures.add(texture);
        return ordinal;
    }

    private static MinecraftMaterialTexture neutral(int r, int g, int b, int a) {
        return new MinecraftMaterialTexture(List.of(new MinecraftMaterialTexture.Mip(1, 1,
                new byte[]{(byte) r, (byte) g, (byte) b, (byte) a})));
    }

    private static MinecraftMaterialTexture texture(int baseSize, List<byte[]> levels) {
        List<MinecraftMaterialTexture.Mip> mipLevels = new ArrayList<>(levels.size());
        int size = baseSize;
        for (byte[] level : levels) {
            mipLevels.add(new MinecraftMaterialTexture.Mip(size, size, level));
            size = Math.max(1, size / 2);
        }
        return new MinecraftMaterialTexture(mipLevels);
    }
}
