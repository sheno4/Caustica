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
    private final ProviderManager providers;
    private final RtMaterialRegistry registry = new RtMaterialRegistry();
    private final RtMaterialPageCompiler pageCompiler = new RtMaterialPageCompiler();
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
                    return registry.bindingId(material.id());
                }

                @Override public int catalog(ResourceId material, ResourceId geometry, MaterialVariant variant) {
                    return registry.requireSnapshot().resolve(material, geometry, variant);
                }

                @Override public int atlas(AtlasMaterialReference reference) {
                    return registry.resolveAtlasReference(reference, false);
                }

                @Override public int standalone(ResourceId material) {
                    return registry.resolveStandaloneTexture(material, false);
                }

                @Override public int fallback() {
                    return registry.runtimeFallbackId();
                }

                @Override public int withTexture(int binding, SceneMesh.TextureReference texture) {
                    return registry.withBaseColorTextureIndex(binding,
                            providers.bindlessTextureSlot(texture));
                }

                @Override public int cutout(int binding) {
                    return registry.withCutoutCoverage(binding);
                }

                @Override public int stochastic(int binding) {
                    return registry.withStochasticCoverage(binding);
                }

                @Override public int sbtClass(int binding) {
                    return registry.sbtClassFor(binding);
                }
            };

    private RtSceneGeometryManager sceneGeometry;
    private RtOpacityMicromapPipeline opacityMicromapPipeline;
    private long sampler;
    private long baseColorAtlasView;
    private long boundBaseColorAtlasView;
    private final LifecycleState state = new LifecycleState();

    public RtMaterialEpoch(ProviderManager providers) {
        this.providers = providers;
    }

    public RtGeometryMaterialResolution.Bindings geometryBindings() {
        return geometryBindings;
    }

    public void attachSceneGeometry(RtSceneGeometryManager sceneGeometry) {
        if (this.sceneGeometry != null) throw new IllegalStateException("Scene geometry is already attached");
        this.sceneGeometry = sceneGeometry;
        sceneGeometry.setMaterialTables(new RtSceneGeometryManager.MaterialTables() {
            @Override public long bindingTableAddress() {
                return registry.bindingTableAddress();
            }

            @Override public long surfaceTableAddress() {
                return registry.surfaceTableAddress();
            }

            @Override public boolean opacityMicromapEligible(int materialId) {
                return registry.opacityMicromapEligible(materialId);
            }
        });
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
        pageCompiler.reset();
        ProviderManager.MaterialContributions contributions = providers.collectMaterials();
        RtMaterialOverrides overrides = RtMaterialOverrides.from(
                contributions.rules(), CausticaApi.registry()::surfaceIndex);
        MaterialCatalog catalog = RtRuntime.host().materialCatalog(contributions.rules());
        pageCompiler.prepareAll(ctx, textureCapacity, catalog);
        if (RtDeviceBringup.ommEnabled()) {
            opacityMicromapPipeline = RtOpacityMicromapPipeline.create(ctx,
                    pageCompiler.temporalAlphaViews(),
                    pageCompiler.staticAlphaViews());
        }
        sceneGeometry.setOpacityMicromapPipeline(opacityMicromapPipeline);
        registry.rebuild(ctx, pageCompiler, catalog, overrides,
                contributions.definitions(), CausticaApi.registry()::surfaceIndex, textureCapacity);
        providers.publishMaterials(registry.requireSnapshot());
        providers.resetBindlessTextures(textureCapacity);
        pipeline.setBaseColorTexture(0, boundBaseColorAtlasView, epochSampler);
        pageCompiler.bindPages(pipeline, epochSampler);
        state.published(textureCapacity);
    }

    /** Bind the already-published epoch into a replacement world pipeline. */
    public void bindCurrent(GpuContext ctx, RtPipeline pipeline) {
        long epochSampler = sampler(ctx);
        pipeline.setBaseColorTexture(0, boundBaseColorAtlasView, epochSampler);
        pageCompiler.bindPages(pipeline, epochSampler);
        providers.rebindTextures(pipeline, epochSampler);
    }

    public void uploadPendingTextures(GpuContext ctx, RtPipeline pipeline) {
        providers.uploadPendingTextures(pipeline, sampler(ctx));
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
    }

    public void reloadFailed() {
        state.reloadFailed();
    }

    public void resourcePackApplied() {
        providers.onResourcePackApplied();
    }

    /** Detach and destroy epoch resources after world-pipeline descriptor references are gone. */
    public void destroyPublishedEpoch() {
        providers.clearMaterials();
        if (sceneGeometry != null) sceneGeometry.setOpacityMicromapPipeline(null);
        if (opacityMicromapPipeline != null) {
            opacityMicromapPipeline.destroy();
            opacityMicromapPipeline = null;
        }
        pageCompiler.reset();
        registry.destroy();
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
