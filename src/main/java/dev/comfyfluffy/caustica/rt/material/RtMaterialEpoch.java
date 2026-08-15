package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.engine.material.AtlasMaterialReference;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialVariant;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.rt.accel.RtOpacityMicromapPipeline;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryMaterialResolution;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;

/** Owns the active immutable resource-pack material epoch and replaces it across reloads. */
public final class RtMaterialEpoch {
    static final class LifecycleState {
        boolean bindingsReady;
        volatile boolean reloadPending;
        int bindlessTextureCapacity;

        void beginReload() {
            reloadPending = true;
            bindingsReady = false;
        }

        void published(int textureCapacity) {
            bindlessTextureCapacity = textureCapacity;
            bindingsReady = true;
            reloadPending = false;
        }

        void destroyPublished() {
            bindingsReady = false;
            bindlessTextureCapacity = 0;
        }

        void reloadFailed() {
            reloadPending = false;
        }

        boolean hasPublishedResources() {
            return bindlessTextureCapacity != 0;
        }
    }

    private final RtGeometryMaterialResolution.Bindings geometryBindings =
            new RtGeometryMaterialResolution.Bindings() {
                @Override public int named(MaterialHandle material) {
                    return RtMaterialRegistry.INSTANCE.bindingId(material.id());
                }

                @Override public int catalog(ResourceId material, ResourceId geometry, MaterialVariant variant) {
                    return RtMaterialRegistry.INSTANCE.requireSnapshot().resolve(material, geometry, variant);
                }

                @Override public int atlas(AtlasMaterialReference reference) {
                    return RtMaterialRegistry.INSTANCE.resolveAtlasReference(reference, false);
                }

                @Override public int standalone(ResourceId material) {
                    return RtMaterialRegistry.INSTANCE.resolveStandaloneTexture(material, false);
                }

                @Override public int fallback() {
                    return RtMaterialRegistry.INSTANCE.runtimeFallbackId();
                }

                @Override public int withTexture(int binding, SceneMesh.TextureReference texture) {
                    return RtMaterialRegistry.INSTANCE.withBaseColorTextureIndex(binding,
                            ProviderManager.INSTANCE.bindlessTextureSlot(texture));
                }

                @Override public int cutout(int binding) {
                    return RtMaterialRegistry.INSTANCE.withCutoutCoverage(binding);
                }

                @Override public int stochastic(int binding) {
                    return RtMaterialRegistry.INSTANCE.withStochasticCoverage(binding);
                }

                @Override public int sbtClass(int binding) {
                    return RtMaterialRegistry.INSTANCE.sbtClassFor(binding);
                }
            };

    private RtSceneGeometryManager sceneGeometry;
    private RtOpacityMicromapPipeline opacityMicromapPipeline;
    private long sampler;
    private long baseColorAtlasView;
    private long boundBaseColorAtlasView;
    private final LifecycleState state = new LifecycleState();

    public RtGeometryMaterialResolution.Bindings geometryBindings() {
        return geometryBindings;
    }

    public void attachSceneGeometry(RtSceneGeometryManager sceneGeometry) {
        if (this.sceneGeometry != null) throw new IllegalStateException("Scene geometry is already attached");
        this.sceneGeometry = sceneGeometry;
    }

    public void observeBaseColorAtlas(long view) {
        baseColorAtlasView = view;
    }

    public boolean bindingsReady() {
        return state.bindingsReady;
    }

    public boolean reloadPending() {
        return state.reloadPending;
    }

    public int bindlessTextureCapacity() {
        return state.bindlessTextureCapacity;
    }

    public boolean waitingForReplacementAtlas() {
        return state.reloadPending && (baseColorAtlasView == 0L || baseColorAtlasView == boundBaseColorAtlasView);
    }

    public boolean atlasReady() {
        return baseColorAtlasView != 0L;
    }

    public boolean hasPublishedResources() {
        return state.hasPublishedResources() || opacityMicromapPipeline != null;
    }

    public void publish(GpuContext ctx, RtPipeline pipeline, int textureCapacity) {
        if (sceneGeometry == null) throw new IllegalStateException("Scene geometry is not attached");
        if (opacityMicromapPipeline != null) {
            throw new IllegalStateException("Previous opacity micromap material epoch is still active");
        }
        long epochSampler = sampler(ctx);
        boundBaseColorAtlasView = baseColorAtlasView;
        RtMaterialPageCompiler.INSTANCE.reset();
        ProviderManager.MaterialContributions contributions = ProviderManager.INSTANCE.collectMaterials();
        RtMaterialOverrides overrides = RtMaterialOverrides.from(
                contributions.rules(), CausticaApi.registry()::surfaceIndex);
        MaterialCatalog catalog = RtRuntime.host().materialCatalog(contributions.rules());
        RtMaterialPageCompiler.INSTANCE.prepareAll(ctx, textureCapacity, catalog);
        if (RtDeviceBringup.ommEnabled()) {
            opacityMicromapPipeline = RtOpacityMicromapPipeline.create(ctx,
                    RtMaterialPageCompiler.INSTANCE.temporalAlphaViews(),
                    RtMaterialPageCompiler.INSTANCE.staticAlphaViews());
        }
        sceneGeometry.setOpacityMicromapPipeline(opacityMicromapPipeline);
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtMaterialPageCompiler.INSTANCE, catalog, overrides,
                contributions.definitions(), CausticaApi.registry()::surfaceIndex, textureCapacity);
        ProviderManager.INSTANCE.resetBindlessTextures(textureCapacity);
        pipeline.setBaseColorTexture(0, boundBaseColorAtlasView, epochSampler);
        RtMaterialPageCompiler.INSTANCE.bindPages(pipeline, epochSampler);
        state.published(textureCapacity);
    }

    /** Bind the already-published epoch into a replacement world pipeline. */
    public void bindCurrent(GpuContext ctx, RtPipeline pipeline) {
        long epochSampler = sampler(ctx);
        pipeline.setBaseColorTexture(0, boundBaseColorAtlasView, epochSampler);
        RtMaterialPageCompiler.INSTANCE.bindPages(pipeline, epochSampler);
        ProviderManager.INSTANCE.rebindTextures(pipeline, epochSampler);
    }

    public void uploadPendingTextures(GpuContext ctx, RtPipeline pipeline) {
        ProviderManager.INSTANCE.uploadPendingTextures(pipeline, sampler(ctx));
    }

    public RtMaterialRegistry.Snapshot snapshot() {
        return RtMaterialRegistry.INSTANCE.requireSnapshot();
    }

    public long bindingTableAddress() {
        return RtMaterialRegistry.INSTANCE.bindingTableAddress();
    }

    public long surfaceTableAddress() {
        return RtMaterialRegistry.INSTANCE.surfaceTableAddress();
    }

    /** Mark provider resources closing before the caller drains work and destroys descriptor-owning pipelines. */
    public void beginReload() {
        state.beginReload();
        ProviderManager.INSTANCE.onResourcePackClosing();
    }

    public void reloadFailed() {
        state.reloadFailed();
    }

    public void resourcePackApplied() {
        ProviderManager.INSTANCE.onResourcePackApplied();
    }

    /** Detach and destroy epoch resources after world-pipeline descriptor references are gone. */
    public void destroyPublishedEpoch() {
        if (sceneGeometry != null) sceneGeometry.setOpacityMicromapPipeline(null);
        if (opacityMicromapPipeline != null) {
            opacityMicromapPipeline.destroy();
            opacityMicromapPipeline = null;
        }
        RtMaterialPageCompiler.INSTANCE.reset();
        RtMaterialRegistry.INSTANCE.destroy();
        state.destroyPublished();
    }

    public void destroy() {
        destroyPublishedEpoch();
        GpuContext ctx = GpuContext.currentOrNull();
        if (sampler != 0L && ctx != null) VK10.vkDestroySampler(ctx.vk(), sampler, null);
        sampler = 0L;
        state.reloadFailed();
        baseColorAtlasView = 0L;
        boundBaseColorAtlasView = 0L;
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
