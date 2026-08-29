package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtOpacityMicromapPipeline;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryMaterialResolution;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.texture.ProviderTextureRegistry;
import dev.comfyfluffy.caustica.rt.texture.UploadedProviderTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

/** Owns the active immutable resource-pack material epoch and replaces it across reloads. */
public final class RtMaterialEpoch {
    /** Shared capacity for provider base textures and four canonical mip resources per compiled page. */
    public static final int TEXTURE_CAPACITY = 1280;
    private final ProviderManager providers;
    private final RtMaterialRegistry registry = new RtMaterialRegistry();
    static final class LifecycleState {
        boolean bindingsReady;
        volatile boolean reloadPending;
        boolean publishedResources;

        void beginReload() {
            reloadPending = true;
            bindingsReady = false;
        }

        void published() {
            publishedResources = true;
            bindingsReady = true;
            reloadPending = false;
        }

        void destroyPublished() {
            bindingsReady = false;
            publishedResources = false;
        }

        void reloadFailed() {
            reloadPending = false;
        }

        boolean hasPublishedResources() {
            return publishedResources;
        }
    }

    private RtGeometryMaterialResolution.Bindings createGeometryBindings(ResourceId source) {
        return
            new RtGeometryMaterialResolution.Bindings() {
                @Override public int named(MaterialHandle material) {
                    return registry.bindingId(material.id());
                }

                @Override public int fallback() {
                    return registry.runtimeFallbackId();
                }

                @Override public int textureSlot(SceneMesh.TextureReference texture) {
                    return textureRegistry.requireSlot(source, texture);
                }

                @Override public int sbtClass(int binding) {
                    return registry.sbtClassFor(binding);
                }
            };
    }

    private RtSceneGeometryManager sceneGeometry;
    private RtOpacityMicromapPipeline opacityMicromapPipeline;
    private ProviderTextureRegistry textureRegistry;
    private long sampler;
    private boolean providerSnapshotPublished;
    private final LifecycleState state = new LifecycleState();

    public RtMaterialEpoch(ProviderManager providers) {
        this.providers = providers;
    }

    public RtGeometryMaterialResolution.Bindings geometryBindings(ResourceId source) {
        return createGeometryBindings(source);
    }

    public void attachSceneGeometry(RtSceneGeometryManager sceneGeometry) {
        if (this.sceneGeometry != null) throw new IllegalStateException("Scene geometry is already attached");
        this.sceneGeometry = sceneGeometry;
        sceneGeometry.setMaterialTables(new RtSceneGeometryManager.MaterialTables() {
            @Override public long bindingTableAddress() {
                return registry.bindingTableAddress();
            }
        });
    }

    public boolean bindingsReady() {
        return state.bindingsReady;
    }

    public boolean reloadPending() {
        return state.reloadPending;
    }

    public boolean hasPublishedResources() {
        return state.hasPublishedResources() || opacityMicromapPipeline != null;
    }

    public void publish(GpuContext ctx, RtPipeline pipeline, int textureCapacity,
                        Set<Integer> rejectedSurfaces) {
        if (sceneGeometry == null) throw new IllegalStateException("Scene geometry is not attached");
        if (opacityMicromapPipeline != null) {
            throw new IllegalStateException("Previous opacity micromap material epoch is still active");
        }
        long epochSampler = sampler(ctx);
        textureRegistry = new ProviderTextureRegistry(textureCapacity,
                (texture, label) -> new UploadedProviderTexture(ctx, texture, label),
                (slot, view, layout) -> pipeline.setProviderTexture(slot, view, layout, epochSampler));
        providers.bindTextureRegistry(textureRegistry);
        ProviderManager.MaterialContributions contributions = providers.collectMaterials();
        RtMaterialRegistry.SurfaceResolver surfaceResolver = surface -> {
            int index = CausticaApi.registry().surfaceIndex(surface);
            return rejectedSurfaces.contains(index) ? RtMaterialRegistry.ERROR_SURFACE_IMPLEMENTATION : index;
        };
        Set<ResourceId> availableSurfaces = new HashSet<>();
        var registeredSurfaces = CausticaApi.registry().surfaces();
        for (int index = 0; index < registeredSurfaces.size(); index++) {
            if (index != RtMaterialRegistry.ERROR_SURFACE_IMPLEMENTATION
                    && !rejectedSurfaces.contains(index)) {
                availableSurfaces.add(registeredSurfaces.get(index).id());
            }
        }
        if (ctx.backend().capabilities().opacityMicromaps()) {
            opacityMicromapPipeline = RtOpacityMicromapPipeline.create(ctx);
        }
        sceneGeometry.setOpacityMicromapPipeline(opacityMicromapPipeline);
        registry.rebuild(ctx, contributions.definitions(), surfaceResolver,
                Set.copyOf(availableSurfaces));
        providers.publishMaterials(registry.requireSnapshot());
        providerSnapshotPublished = true;
        state.published();
    }

    public int textureSlot(ResourceId source, SceneMesh.TextureReference texture) {
        if (texture == null) return 0;
        if (textureRegistry == null) throw new IllegalStateException("provider texture epoch is not published");
        return textureRegistry.requireSlot(source, texture);
    }

    public MaterialEpochSnapshot snapshot() {
        return registry.requireSnapshot();
    }

    public long bindingTableAddress() {
        return registry.bindingTableAddress();
    }

    public long surfaceTableAddress() {
        return registry.surfaceTableAddress();
    }

    /** Mark provider resources closing before the caller drains work and destroys descriptor-owning pipelines. */
    public void beginReload() {
        state.beginReload();
        providers.onResourcePackClosing();
        providerSnapshotPublished = false;
    }

    public void reloadFailed() {
        state.reloadFailed();
    }

    public void resourcePackApplied() {
        providers.onResourcePackApplied();
    }

    /** Detach and destroy epoch resources after world-pipeline descriptor references are gone. */
    public void destroyPublishedEpoch() {
        if (providerSnapshotPublished) {
            providers.clearMaterials();
            providerSnapshotPublished = false;
        }
        if (sceneGeometry != null) sceneGeometry.setOpacityMicromapPipeline(null);
        if (opacityMicromapPipeline != null) {
            opacityMicromapPipeline.destroy();
            opacityMicromapPipeline = null;
        }
        registry.destroy();
        if (textureRegistry != null) {
            providers.unbindTextureRegistry(textureRegistry);
            textureRegistry.close();
            textureRegistry = null;
        }
        state.destroyPublished();
    }

    public void destroy() {
        destroyPublishedEpoch();
        GpuContext ctx = GpuContext.currentOrNull();
        if (sampler != 0L && ctx != null) VK10.vkDestroySampler(ctx.vk(), sampler, null);
        sampler = 0L;
        state.reloadFailed();
    }

    private long sampler(GpuContext ctx) {
        if (sampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer out = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), info, null, out) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(material textures) failed");
                }
                sampler = out.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, "material texture sampler");
            }
        }
        return sampler;
    }
}
