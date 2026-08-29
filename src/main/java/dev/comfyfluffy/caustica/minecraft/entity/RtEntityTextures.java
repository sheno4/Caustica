package dev.comfyfluffy.caustica.minecraft.entity;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.MinecraftResourceIds;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.mixin.RenderTypeAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves Minecraft textures to stable source-local references and borrowed Vulkan image views.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}), keyed by
 * the material sampler named {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are auxiliary
 * bindings). Resolution is cached per {@code
 * RenderType} (they are stable singletons), so the prepare() cost is paid once per distinct texture.
 */
public final class RtEntityTextures {
    public static final RtEntityTextures INSTANCE = new RtEntityTextures();

    // RenderType identity → resolved primary image-view handle. WEAK: some render types are rebuilt
    // every frame with a fresh identity (e.g. the charged-creeper energy-swirl layer, whose scrolling
    // texture transform makes RenderTypes.energySwirl() allocate a new RenderType each frame). A weak map
    // lets those dead identities be collected instead of accumulating; stable singletons (zombie.png, …)
    // stay cached and skip the costly RenderType.prepare().
    private final Map<RenderType, Long> viewCache = new WeakHashMap<>();
    private final Map<RenderType, Identifier> locationCache = new WeakHashMap<>();
    private final Map<MinecraftEntityMesh.Texture, BorrowedTextureView> contributions = new HashMap<>();
    private boolean loggedFailure;
    private boolean loggedMaterialFailure;
    // 1x1 solid-white DynamicTexture for untextured geometry (leash/line ribbons).
    private static final Identifier WHITE_LOCATION = Identifier.fromNamespaceAndPath("caustica", "rt_white");
    private boolean whiteRegistered;

    // Cached RenderSetup.TextureBinding#location() (the class is package-private, the method public).
    private Method locationMethod;

    private RtEntityTextures() {
    }

    /** Contribute the primary texture of a render type under its stable source-local reference. */
    public void contribute(RenderType renderType, MinecraftEntityMesh.Texture reference) {
        if (renderType == null || reference == null || contributions.containsKey(reference)) return;
        contribute(reference, resolveView(renderType));
    }

    /** Resolve and contribute the stable logical texture used by a render type. */
    public MinecraftEntityMesh.Texture contribute(RenderType renderType) {
        Identifier location = textureLocation(renderType);
        if (location == null) return null;
        MinecraftEntityMesh.Texture reference = MinecraftEntityMesh.Texture.standalone(
                MinecraftResourceIds.logicalTexture(location));
        contribute(renderType, reference);
        return reference;
    }

    /** Contribute a Minecraft atlas under the same reference stored in submitted scene meshes. */
    public MinecraftEntityMesh.Texture contributeAtlas(Identifier atlasLocation) {
        if (atlasLocation == null) return null;
        MinecraftEntityMesh.Texture reference = MinecraftEntityMesh.Texture.atlas(
                ResourceId.of(atlasLocation.getNamespace(), atlasLocation.getPath()));
        if (!contributions.containsKey(reference)) {
            long view = 0L;
            try {
                GpuTextureView textureView = Minecraft.getInstance().getTextureManager()
                        .getTexture(atlasLocation).getTextureView();
                view = vkImageView(textureView);
            } catch (Throwable failure) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    CausticaMod.LOGGER.warn("RT atlas texture resolution failed for {}", atlasLocation, failure);
                }
            }
            contribute(reference, view);
        }
        return reference;
    }

    private void contribute(MinecraftEntityMesh.Texture reference, long imageView) {
        if (imageView == 0L) return;
        contributions.put(reference, new BorrowedTextureView(
                imageView, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL));
    }

    /** Borrow the current Vulkan view for upload-time descriptor allocation, or {@code null} if unresolved. */
    public BorrowedTextureView borrow(MinecraftEntityMesh.Texture texture) {
        return contributions.get(texture);
    }

    /** Minecraft-owned image view borrowed only for descriptor encoding in the active resource epoch. */
    public record BorrowedTextureView(long imageView, int imageLayout) { }

    /** Stable source identity for the registered white texture used by untextured geometry. */
    public MinecraftEntityMesh.Texture whiteTexture() {
        ensureWhiteTexture();
        return contributeAtlas(WHITE_LOCATION);
    }

    private void ensureWhiteTexture() {
        if (whiteRegistered) return;
        whiteRegistered = true;
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, 0xFFFFFFFF);
        Minecraft.getInstance().getTextureManager()
                .register(WHITE_LOCATION, new DynamicTexture(() -> "caustica RT white", image));
    }

    /** Drop resource-pack-owned view identities after the renderer has detached their descriptor epoch. */
    public void reset() {
        viewCache.clear();
        locationCache.clear();
        contributions.clear();
    }

    /** Recover the resource identifier used to select {@code renderType}'s material, or null. The
     *  {@code RenderSetup.TextureBinding} class is package-private, so {@code location()} is reflective. */
    Identifier textureLocation(RenderType renderType) {
        if (renderType == null) return null;
        if (locationCache.containsKey(renderType)) return locationCache.get(renderType);
        Identifier result = null;
        try {
            // RenderSetup is final, so the accessor cast must go through Object (the interface is only
            // mixed in at runtime); RenderType is non-final so its cast is fine directly.
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            Map<String, ?> textures = ((RenderSetupAccessor) setup).caustica$textures();
            Object binding = textures.get("Sampler0");
            if (binding == null) {
                locationCache.put(renderType, null);
                return null;
            }
            if (locationMethod == null) {
                locationMethod = binding.getClass().getMethod("location");
                locationMethod.setAccessible(true);
            }
            result = (Identifier) locationMethod.invoke(binding);
        } catch (Throwable t) {
            warnMaterialOnce("RT entity texture Identifier resolution failed for " + renderType, t);
        }
        locationCache.put(renderType, result);
        return result;
    }

    private void warnMaterialOnce(String msg, Throwable t) {
        if (!loggedMaterialFailure) {
            loggedMaterialFailure = true;
            CausticaMod.LOGGER.warn(msg, t);
        }
    }

    /** The Vulkan image-view handle of {@code renderType}'s primary texture, or 0 if it can't be resolved. */
    public long resolveView(RenderType renderType) {
        if (renderType == null) {
            return 0L;
        }
        Long cached = viewCache.get(renderType);
        if (cached != null) {
            return cached;
        }
        long handle = 0L;
        try {
            PreparedRenderType prepared = renderType.prepare();
            String wanted = "Sampler0";
            GpuTextureView chosen = null;
            GpuTextureView firstNonAux = null;
            for (PreparedRenderType.Texture t : prepared.textures()) {
                String name = t.name();
                if (wanted.equals(name)) {
                    chosen = t.textureView();
                    break;
                }
                if (firstNonAux == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) {
                    firstNonAux = t.textureView();
                }
            }
            if (chosen == null) {
                chosen = firstNonAux;
            }
            if (chosen != null) {
                handle = vkImageView(chosen);
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        viewCache.put(renderType, handle);
        return handle;
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
