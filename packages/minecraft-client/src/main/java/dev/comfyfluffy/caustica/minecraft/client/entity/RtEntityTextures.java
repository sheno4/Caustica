package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftTextureLifetime;
import dev.comfyfluffy.caustica.minecraft.rendering.entity.EntityTextureResolver;
import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityMesh;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.MinecraftTextureSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.GpuFormat;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftResourceIds;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.minecraft.client.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.minecraft.client.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.minecraft.client.mixin.TextureBindingAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves Minecraft textures to stable source-local references and borrowed Vulkan image views.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}), keyed by
 * the material sampler named {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are auxiliary
 * bindings). Weak render-type keys retain cached bindings while each render type remains in use.
 */
public final class RtEntityTextures implements EntityTextureResolver {

    // RenderType identity → resolved primary image-view handle. WEAK: some render types are rebuilt
    // every frame with a fresh identity (e.g. the charged-creeper energy-swirl layer, whose scrolling
    // texture transform makes RenderTypes.energySwirl() allocate a new RenderType each frame). A weak map
    // lets those dead identities be collected instead of accumulating; stable singletons (zombie.png, …)
    // stay cached and skip the costly RenderType.prepare().
    private final Map<RenderType, CapturedBinding> bindingCache = new WeakHashMap<>();
    private final Map<RenderType, Identifier> locationCache = new WeakHashMap<>();
    private final Map<MinecraftEntityMesh.Texture, VulkanGpuTextureView> contributions = new HashMap<>();
    private boolean loggedFailure;
    // 1x1 solid-white DynamicTexture for untextured geometry (leash/line ribbons).
    private static final Identifier WHITE_LOCATION = Identifier.fromNamespaceAndPath("caustica", "rt_white");
    private boolean whiteRegistered;

    public RtEntityTextures() {
    }

    /** Resolve and contribute the stable logical texture used by a render type. */
    public MinecraftEntityMesh.Texture contribute(RenderType renderType) {
        Identifier location = textureLocation(renderType);
        if (location == null) return null;
        CapturedBinding binding = resolveBinding(renderType);
        MinecraftEntityMesh.Texture reference = MinecraftEntityMesh.Texture.standalone(
                MinecraftResourceIds.logicalTexture(location), binding == null
                        ? MinecraftTextureSampler.PIXEL_ART : binding.sampler());
        contribute(reference, binding == null ? null : binding.view());
        return reference;
    }

    /** Contribute a Minecraft atlas under the same reference stored in submitted scene meshes. */
    public MinecraftEntityMesh.Texture contributeAtlas(Identifier atlasLocation) {
        if (atlasLocation == null) return null;
        MinecraftEntityMesh.Texture reference;
        VulkanGpuTextureView view = null;
        MinecraftTextureSampler sampler = MinecraftTextureSampler.PIXEL_ART;
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(atlasLocation);
            view = vkView(texture.getTextureView());
            sampler = sampler(texture.getSampler());
        } catch (Throwable failure) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT atlas texture resolution failed for {}", atlasLocation, failure);
            }
        }
        reference = MinecraftEntityMesh.Texture.atlas(
                ResourceId.of(atlasLocation.getNamespace(), atlasLocation.getPath()), sampler);
        contribute(reference, view);
        return reference;
    }

    private void contribute(MinecraftEntityMesh.Texture reference, VulkanGpuTextureView view) {
        if (view == null || view.texture().getFormat() != GpuFormat.RGBA8_UNORM) return;
        contributions.put(reference, view);
    }

    /** Borrow the current Vulkan view for upload-time descriptor allocation, or {@code null} if unresolved. */
    @Override public BorrowedMinecraftTexture resolve(MinecraftEntityMesh.Texture texture) {
        VulkanGpuTextureView view = contributions.get(texture);
        if (view == null) return null;
        MinecraftTextureLifetime.retain(view.texture());
        return new Borrow(view, texture.sampler());
    }

    /** Stable source identity for the registered white texture used by untextured geometry. */
    public MinecraftEntityMesh.Texture whiteTexture() {
        ensureWhiteTexture();
        return contributeAtlas(WHITE_LOCATION);
    }

    private void ensureWhiteTexture() {
        if (whiteRegistered) return;
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, 0xFFFFFFFF);
        Minecraft.getInstance().getTextureManager()
                .register(WHITE_LOCATION, new DynamicTexture(() -> "caustica RT white", image));
        whiteRegistered = true;
    }

    /** Drop resource-pack-owned view identities after the renderer has detached their descriptor epoch. */
    public void reset() {
        bindingCache.clear();
        locationCache.clear();
        contributions.clear();
    }

    /** Recover the primary texture's resource identifier, or null for a render type without Sampler0. */
    Identifier textureLocation(RenderType renderType) {
        if (renderType == null) return null;
        if (locationCache.containsKey(renderType)) return locationCache.get(renderType);
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        Map<String, ?> textures = ((RenderSetupAccessor) setup).caustica$textures();
        var binding = (TextureBindingAccessor) textures.get("Sampler0");
        Identifier result = binding == null ? null : binding.caustica$location();
        locationCache.put(renderType, result);
        return result;
    }

    private CapturedBinding resolveBinding(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        CapturedBinding cached = bindingCache.get(renderType);
        if (cached != null || bindingCache.containsKey(renderType)) {
            return cached;
        }
        CapturedBinding handle = null;
        try {
            PreparedRenderType prepared = renderType.prepare();
            PreparedRenderType.Texture chosen = null;
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                String name = texture.name();
                if ("Sampler0".equals(name)) {
                    chosen = texture;
                    break;
                }
                if (chosen == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) {
                    chosen = texture;
                }
            }
            if (chosen != null) {
                VulkanGpuTextureView view = vkView(chosen.textureView());
                if (view != null && chosen.sampler() != null) {
                    handle = new CapturedBinding(view, sampler(chosen.sampler()));
                }
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        bindingCache.put(renderType, handle);
        return handle;
    }

    static MinecraftTextureSampler sampler(GpuSampler sampler) {
        double sourceMaxLod = sampler.getMaxLod().orElse(1000.0);
        float maxLod = (float) Math.max(0.25, Math.min(sourceMaxLod, Float.MAX_VALUE));
        return new MinecraftTextureSampler(filter(sampler.getMinFilter()), filter(sampler.getMagFilter()),
                address(sampler.getAddressModeU()), address(sampler.getAddressModeV()),
                maxLod > 0.25f ? MinecraftTextureSampler.MipmapMode.LINEAR
                        : MinecraftTextureSampler.MipmapMode.NEAREST,
                maxLod, sampler.getMaxAnisotropy());
    }

    private static MinecraftTextureSampler.Filter filter(FilterMode value) {
        return value == FilterMode.NEAREST
                ? MinecraftTextureSampler.Filter.NEAREST : MinecraftTextureSampler.Filter.LINEAR;
    }

    private static MinecraftTextureSampler.AddressMode address(AddressMode value) {
        return value == AddressMode.REPEAT
                ? MinecraftTextureSampler.AddressMode.REPEAT : MinecraftTextureSampler.AddressMode.CLAMP_TO_EDGE;
    }

    private static VulkanGpuTextureView vkView(GpuTextureView view) {
        return view instanceof VulkanGpuTextureView vulkanView ? vulkanView : null;
    }

    private static final class Borrow implements BorrowedMinecraftTexture {
        private final VulkanGpuTextureView view;
        private final MinecraftTextureSampler sampler;
        private boolean closed;
        private Borrow(VulkanGpuTextureView view, MinecraftTextureSampler sampler) {
            this.view = view;
            this.sampler = sampler;
        }
        @Override public long vkImage() { return view.texture().vkImage(); }
        @Override public int format() { return org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM; }
        @Override public int baseMipLevel() { return view.baseMipLevel(); }
        @Override public int mipLevels() { return view.mipLevels(); }
        @Override public int imageLayout() { return org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL; }
        @Override public MinecraftTextureSampler sampler() { return sampler; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            MinecraftTextureLifetime.release(view.texture());
        }
    }

    private record CapturedBinding(VulkanGpuTextureView view, MinecraftTextureSampler sampler) { }
}
