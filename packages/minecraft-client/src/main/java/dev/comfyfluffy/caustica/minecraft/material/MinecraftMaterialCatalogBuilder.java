package dev.comfyfluffy.caustica.minecraft.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.MinecraftResourceIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialEmissionIndex;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialImage;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialImageSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureResource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialImage;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialRule;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTextureSource;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Collects Minecraft material texture resources from atlases and resource packs. */
public final class MinecraftMaterialCatalogBuilder {
    private MinecraftMaterialCatalogBuilder() {
    }

    public static List<MaterialTextureResource> build(List<MinecraftMaterialRule> rules,
                                                       MinecraftLightingCalibration calibration) {
        MaterialEmissionIndex emissions = MinecraftEmissionSemantics.analyze();
        float uniformEmissionLuminance = calibration.blockEmissionLuminanceCdM2();
        List<TextureAtlasSprite> sprites = blockSprites();
        List<MaterialTextureResource> blocks = new ArrayList<>();
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            Identifier name = sprite.contents().name();
            ResourceId material = MinecraftResourceIds.resourceId(name);
            Optional<Resource> spec = resource(sibling(name, "_s.png"));
            Optional<Resource> normal = resource(sibling(name, "_n.png"));
            NativeImage original = ((SpriteContentsAccessor) sprite.contents()).caustica$originalImage();
            int[] alphaFrames = exhaustiveAlphaFrames(sprite.contents().getUniqueFrames().toIntArray(),
                    original.getWidth(), original.getHeight(),
                    sprite.contents().width(), sprite.contents().height());
            MaterialImageSource albedo = borrowed(original, sprite.contents().width(), sprite.contents().height());
            MaterialImageSource specular = spec.map(value -> resourceImage(value, true)).orElse(null);
            MaterialImageSource normalMap = normal.map(value -> resourceImage(value, true)).orElse(null);
            boolean inferEmission = spec.isEmpty() && emissions.permits(material);
            blocks.add(new MaterialTextureResource(material, MaterialTextureKind.SHARED_ATLAS,
                    new MaterialTextureAnalysisSource(sprite.contents().width(), sprite.contents().height(),
                            alphaFrames.length,
                            new MinecraftMaterialTextureSource(albedo, specular, normalMap, inferEmission,
                                    alphaFrames,
                                    Math.max(1, original.getWidth() / sprite.contents().width()))),
                    new MaterialUv(sprite.getU0(), sprite.getV0(),
                            inverseExtent(sprite.getU1() - sprite.getU0()),
                            inverseExtent(sprite.getV1() - sprite.getV0())),
                    spec.isPresent(), normal.isPresent(), spec.isPresent() || inferEmission,
                    OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR,
                    MinecraftMaterialClassifier.dielectricIor(material), uniformEmissionLuminance));
        }

        Set<ResourceId> blockNames = new HashSet<>();
        blocks.forEach(resource -> blockNames.add(resource.material()));
        blocks.addAll(standaloneResources(blockNames, rules, uniformEmissionLuminance));
        return List.copyOf(blocks);
    }

    private static List<MaterialTextureResource> standaloneResources(Set<ResourceId> blockNames,
                                                                      List<MinecraftMaterialRule> rules,
                                                                      float uniformEmissionLuminance) {
        Map<Identifier, Integer> discovered = discoverStandalone(blockNames, rules);
        List<MaterialTextureResource> result = new ArrayList<>();
        for (Map.Entry<Identifier, Integer> entry : discovered.entrySet()) {
            Identifier albedoLocation = entry.getKey();
            Optional<Resource> albedoResource = resource(albedoLocation);
            if (albedoResource.isEmpty()) continue;
            try (MaterialImage albedo = resourceImage(albedoResource.get(), false).open()) {
                if (albedo.width() <= 0 || albedo.height() <= 0) continue;
                ResourceId material = MinecraftResourceIds.logicalTexture(albedoLocation);
                int features = entry.getValue();
                Optional<Resource> spec = (features & 1) != 0
                        ? resource(siblingTexture(albedoLocation, "_s")) : Optional.empty();
                Optional<Resource> normal = (features & 2) != 0
                        ? resource(siblingTexture(albedoLocation, "_n")) : Optional.empty();
                MaterialImageSource albedoSource = resourceImage(albedoResource.get(), false);
                MaterialImageSource specular = spec.map(value -> resourceImage(value, false)).orElse(null);
                MaterialImageSource normalMap = normal.map(value -> resourceImage(value, false)).orElse(null);
                result.add(new MaterialTextureResource(material, MaterialTextureKind.STANDALONE,
                        new MaterialTextureAnalysisSource(albedo.width(), albedo.height(), 1,
                                new MinecraftMaterialTextureSource(albedoSource, specular, normalMap, false)),
                        MaterialUv.IDENTITY, spec.isPresent(), normal.isPresent(), spec.isPresent(),
                        OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR,
                        MinecraftMaterialClassifier.dielectricIor(material), uniformEmissionLuminance));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("RT entity material albedo load failed for {}", albedoLocation, throwable);
            }
        }
        return result;
    }

    private static Map<Identifier, Integer> discoverStandalone(Set<ResourceId> blockNames,
                                                                List<MinecraftMaterialRule> rules) {
        Map<Identifier, Integer> result = new LinkedHashMap<>();
        Map<Identifier, Resource> authored = Minecraft.getInstance().getResourceManager().listResources(
                "textures", id -> id.getPath().endsWith("_s.png") || id.getPath().endsWith("_n.png"));
        List<Identifier> ordered = new ArrayList<>(authored.keySet());
        ordered.sort(Comparator.comparing(Identifier::toString));
        for (Identifier companion : ordered) {
            String path = companion.getPath();
            boolean spec = path.endsWith("_s.png");
            Identifier albedo = Identifier.fromNamespaceAndPath(companion.getNamespace(),
                    path.substring(0, path.length() - 6) + ".png");
            ResourceId material = MinecraftResourceIds.logicalTexture(albedo);
            if (blockNames.contains(material) || resource(albedo).isEmpty()) continue;
            result.merge(albedo, spec ? 1 : 2, (a, b) -> a | b);
        }
        for (MinecraftMaterialRule rule : rules) {
            if (rule.geometry() != null || blockNames.contains(rule.material())) continue;
            Identifier albedo = textureLocation(rule.material());
            if (resource(albedo).isPresent()) result.merge(albedo, 0, (a, b) -> a | b);
        }
        return result;
    }

    private static List<TextureAtlasSprite> blockSprites() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        return sprites != null ? sprites : List.of();
    }

    private static MaterialImageSource borrowed(NativeImage image, int width, int height) {
        if (image == null) return () -> new NeutralImage(width, height);
        return () -> new MinecraftMaterialImage(image::getPixel, width, height, () -> { });
    }

    private static MaterialImageSource resourceImage(Resource resource, boolean firstFrameOnly) {
        return () -> {
            NativeImage image;
            try (InputStream input = resource.open()) {
                image = NativeImage.read(input);
            }
            int height = firstFrameOnly ? Math.min(image.getHeight(), image.getWidth()) : image.getHeight();
            return new MinecraftMaterialImage(image::getPixel, image.getWidth(), height, image::close);
        };
    }

    private static Optional<Resource> resource(Identifier location) {
        return Minecraft.getInstance().getResourceManager().getResource(location);
    }

    private static Identifier sibling(Identifier name, String suffix) {
        return Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + suffix);
    }

    private static Identifier siblingTexture(Identifier albedo, String suffix) {
        String path = albedo.getPath();
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return Identifier.fromNamespaceAndPath(albedo.getNamespace(), path + suffix + ".png");
    }

    private static Identifier textureLocation(ResourceId material) {
        return Identifier.fromNamespaceAndPath(material.namespace(), "textures/" + material.path() + ".png");
    }

    private static float inverseExtent(float extent) {
        return 1.0f / extent;
    }

    static int[] exhaustiveAlphaFrames(int[] uniqueFrames, int rawWidth, int rawHeight,
                                       int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0 || rawWidth < frameWidth || rawHeight < frameHeight) {
            throw new IllegalArgumentException("Invalid animation sheet dimensions");
        }
        int columns = rawWidth / frameWidth;
        int rows = rawHeight / frameHeight;
        int frameSlots = Math.multiplyExact(columns, rows);
        boolean invalidMetadata = java.util.Arrays.stream(uniqueFrames)
                .anyMatch(frame -> frame < 0 || frame >= frameSlots);
        if (invalidMetadata) {
            return java.util.stream.IntStream.range(0, frameSlots).toArray();
        }
        int[] valid = java.util.Arrays.stream(uniqueFrames)
                .distinct()
                .toArray();
        return valid.length == 0 ? new int[]{0} : valid;
    }

    private record NeutralImage(int width, int height) implements MaterialImage {
        @Override
        public int argb(int x, int y) {
            return -1;
        }

        @Override
        public void close() {
        }
    }
}
