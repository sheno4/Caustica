package dev.comfyfluffy.caustica.minecraft.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialEmissionIndex;
import dev.comfyfluffy.caustica.engine.material.MaterialImage;
import dev.comfyfluffy.caustica.engine.material.MaterialImageSource;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.engine.material.MaterialUv;
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

/** Builds the renderer's neutral material catalog from Minecraft atlases and resource packs. */
public final class MinecraftMaterialCatalogBuilder {
    private MinecraftMaterialCatalogBuilder() {
    }

    public static MaterialCatalog build(List<MaterialRule> rules) {
        MaterialEmissionIndex emissions = MinecraftEmissionSemantics.analyze();
        List<TextureAtlasSprite> sprites = blockSprites();
        List<MaterialTextureAsset> blocks = new ArrayList<>();
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            Identifier name = sprite.contents().name();
            ResourceId material = resourceId(name);
            Optional<Resource> spec = resource(sibling(name, "_s.png"));
            Optional<Resource> normal = resource(sibling(name, "_n.png"));
            NativeImage original = ((SpriteContentsAccessor) sprite.contents()).caustica$originalImage();
            MaterialImageSource albedo = borrowed(original, sprite.contents().width(), sprite.contents().height());
            blocks.add(new MaterialTextureAsset(material, MaterialTextureKind.SHARED_ATLAS,
                    sprite.contents().width(), sprite.contents().height(), albedo,
                    spec.map(value -> resourceImage(value, true)).orElse(null),
                    normal.map(value -> resourceImage(value, true)).orElse(null),
                    new MaterialUv(sprite.getU0(), sprite.getV0(),
                            inverseExtent(sprite.getU1() - sprite.getU0()),
                            inverseExtent(sprite.getV1() - sprite.getV0())),
                    spec.isEmpty() && emissions.permits(material),
                    MinecraftMaterialClassifier.dielectricIor(material)));
        }

        Set<ResourceId> blockNames = new HashSet<>();
        blocks.forEach(asset -> blockNames.add(asset.material()));
        List<MaterialTextureAsset> standalone = standaloneAssets(blockNames, rules);
        ResourceId lava = ResourceId.of("minecraft", "block/lava_still");
        return new MaterialCatalog(blocks, standalone,
                blocks.stream().anyMatch(asset -> asset.material().equals(lava)) ? lava : null);
    }

    private static List<MaterialTextureAsset> standaloneAssets(Set<ResourceId> blockNames,
                                                                List<MaterialRule> rules) {
        Map<Identifier, Integer> discovered = discoverStandalone(blockNames, rules);
        List<MaterialTextureAsset> result = new ArrayList<>();
        for (Map.Entry<Identifier, Integer> entry : discovered.entrySet()) {
            Identifier albedoLocation = entry.getKey();
            Optional<Resource> albedoResource = resource(albedoLocation);
            if (albedoResource.isEmpty()) continue;
            try (MaterialImage albedo = resourceImage(albedoResource.get(), false).open()) {
                if (albedo.width() <= 0 || albedo.height() <= 0) continue;
                ResourceId material = MinecraftMaterialLookup.logicalTexture(albedoLocation);
                int features = entry.getValue();
                Optional<Resource> spec = (features & 1) != 0
                        ? resource(siblingTexture(albedoLocation, "_s")) : Optional.empty();
                Optional<Resource> normal = (features & 2) != 0
                        ? resource(siblingTexture(albedoLocation, "_n")) : Optional.empty();
                result.add(new MaterialTextureAsset(material, MaterialTextureKind.STANDALONE,
                        albedo.width(), albedo.height(), resourceImage(albedoResource.get(), false),
                        spec.map(value -> resourceImage(value, false)).orElse(null),
                        normal.map(value -> resourceImage(value, false)).orElse(null),
                        MaterialUv.IDENTITY, false, MinecraftMaterialClassifier.dielectricIor(material)));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("RT entity material albedo load failed for {}", albedoLocation, throwable);
            }
        }
        return result;
    }

    private static Map<Identifier, Integer> discoverStandalone(Set<ResourceId> blockNames,
                                                                List<MaterialRule> rules) {
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
            ResourceId material = MinecraftMaterialLookup.logicalTexture(albedo);
            if (blockNames.contains(material) || resource(albedo).isEmpty()) continue;
            result.merge(albedo, spec ? 1 : 2, (a, b) -> a | b);
        }
        for (MaterialRule rule : rules) {
            if (rule.match().geometry() != null || blockNames.contains(rule.match().material())) continue;
            Identifier albedo = textureLocation(rule.match().material());
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
        return () -> new MinecraftMaterialImage(image, width, height, false);
    }

    private static MaterialImageSource resourceImage(Resource resource, boolean firstFrameOnly) {
        return () -> {
            NativeImage image;
            try (InputStream input = resource.open()) {
                image = NativeImage.read(input);
            }
            int height = firstFrameOnly ? Math.min(image.getHeight(), image.getWidth()) : image.getHeight();
            return new MinecraftMaterialImage(image, image.getWidth(), height, true);
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

    private static ResourceId resourceId(Identifier id) {
        return ResourceId.of(id.getNamespace(), id.getPath());
    }

    private static float inverseExtent(float extent) {
        return 1.0f / extent;
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
