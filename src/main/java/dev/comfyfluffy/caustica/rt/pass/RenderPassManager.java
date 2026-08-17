package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.OptionValues;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.rt.VulkanBarriers;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;

/**
 * Sequences registered {@link CausticaRenderPass}es by stage and drives their lifecycle. Each pass owns
 * its own Vulkan resources outright — this class does not allocate, track, or barrier anything on a
 * pass's behalf beyond the two engine-produced inputs ({@link #setReconstructedColor}/
 * {@link #setExposureImage}), the post chain it rotates between {@link PassFrame#sceneColorTarget}s, and
 * the named world-resource registry passes publish into via {@link PassSetup#publishWorldResource}.
 * A pass that throws from any lifecycle method is disabled with a logged error and everything it had
 * published is unpublished in the same step, so a later reader never resolves an image the pass's own
 * cleanup is about to free; the frame loop continues without it, matching {@code ProviderManager}'s
 * isolation discipline.
 */
public final class RenderPassManager {
    private final GpuContext ctx;
    private final List<CausticaRenderPass> ordered;
    private final Set<CausticaRenderPass> disabled = new HashSet<>();
    private final Set<CausticaRenderPass> destroyed = new HashSet<>();
    private final Map<String, WorldResource> worldResources = new LinkedHashMap<>();
    private final Map<String, CausticaRenderPass> worldResourcePublishers = new LinkedHashMap<>();
    private final Map<ResourceId, Feature> passFeature;
    private final CausticaOptions optionsStore;
    private GpuImage reconstructedColor;
    private GpuImage exposureImage;
    /** The two images the post chain rotates between; {@code null} until the engine sizes them. */
    private final GpuImage[] sceneColorTargets = new GpuImage[2];
    private GpuImage sceneColor;
    private int nextSceneColorTarget;
    private int worldResourceGeneration;
    private int displayWidth;
    private int displayHeight;
    private long frameIndex = -1;
    private Map<String, Object> frameOptionsSnapshot = Map.of();
    private GpuFrameUse gpuUse;
    private long worldTlas;
    private final Matrix4f worldViewProjection = new Matrix4f();
    private BiFunction<ResourceId, SceneMesh.TextureReference, Integer> textureResolver =
            (source, texture) -> { throw new IllegalStateException("provider texture epoch is not available"); };

    public void setTextureResolver(BiFunction<ResourceId, SceneMesh.TextureReference, Integer> resolver) {
        textureResolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * A pass-published world resource: exactly one of an owned image, a buffer, or a raw view of an image
     * the host application owns, matching whichever {@code publishWorldResource} overload the pass called
     * — driven by whichever descriptor kind that pass's own Slang declared (see
     * {@code WorldShaderCompiler.PassResourceKind}).
     */
    public record WorldResource(GpuImage image, long sampler, GpuBuffer buffer, long rawImageView) {
        public WorldResource {
            int kinds = (image != null ? 1 : 0) + (buffer != null ? 1 : 0) + (rawImageView != 0L ? 1 : 0);
            if (kinds != 1) {
                throw new IllegalArgumentException(
                        "a world resource is exactly one of an image, a buffer, or a raw image view");
            }
        }

        /** The Vulkan image view to bind, whichever image form this resource took. */
        public long view() {
            return image != null ? image.view() : rawImageView;
        }

        static WorldResource ofImage(GpuImage image, long sampler) {
            return new WorldResource(image, sampler, null, 0L);
        }

        static WorldResource ofBuffer(GpuBuffer buffer) {
            return new WorldResource(null, 0L, buffer, 0L);
        }

        static WorldResource ofRawView(long imageView, long sampler) {
            return new WorldResource(null, sampler, null, imageView);
        }
    }

    RenderPassManager(GpuContext ctx, List<CausticaRenderPass> ordered) {
        this(ctx, ordered, Map.of(), null);
    }

    private RenderPassManager(GpuContext ctx, List<CausticaRenderPass> ordered,
                              Map<ResourceId, Feature> passFeature, CausticaOptions optionsStore) {
        this.ctx = ctx;
        this.ordered = ordered;
        this.passFeature = passFeature;
        this.optionsStore = optionsStore;
    }

    /**
     * {@code contributions} contains only the render-session instances selected by the runtime closure.
     * Options remain process-scoped because their values outlive both the device and RT sessions.
     */
    public static RenderPassManager create(GpuContext ctx, CausticaRegistry.RuntimeContributions contributions,
                                           CausticaOptions options) {
        List<CausticaRenderPass> ordered = orderPasses(contributions.renderPasses().values());
        RenderPassManager manager = new RenderPassManager(ctx, ordered, contributions.renderPassFeatures(), options);
        for (CausticaRenderPass pass : ordered) {
            PassSetup setup = manager.new Setup(pass);
            manager.invoke(pass, "create", () -> pass.create(setup));
        }
        if (RtRuntime.INSTANCE.hasAppliedResourcePack()) {
            manager.onResourcePackApplied();
        }
        return manager;
    }

    public void resize(int width, int height) {
        if (displayWidth == width && displayHeight == height) {
            return;
        }
        displayWidth = width;
        displayHeight = height;
        for (CausticaRenderPass pass : ordered) {
            if (disabled.contains(pass)) {
                continue;
            }
            PassSetup setup = new Setup(pass);
            invoke(pass, "resize", () -> pass.resize(setup, width, height));
        }
    }

    /** Engine-produced input: the reconstructed HDR colour target, set once per frame before recording. */
    public void setReconstructedColor(GpuImage image) {
        reconstructedColor = image;
    }

    /**
     * The pair of display-res images the post chain rotates between. The reconstructed colour is never
     * one of them: it seeds the chain read-only, so a pass writing the first link cannot be reading and
     * writing the same image, and the engine's own consumers of the raw reconstruction (auto-exposure
     * metering, the debug scene view) still see it unmodified.
     */
    public void setSceneColorTargets(GpuImage first, GpuImage second) {
        sceneColorTargets[0] = first;
        sceneColorTargets[1] = second;
    }

    /** Engine-produced input: this frame's scalar exposure value, set once per frame before recording. */
    public void setExposureImage(GpuImage image) {
        exposureImage = image;
    }

    /**
     * Reset per-frame bookkeeping; call once before any recording. Also takes this frame's option
     * snapshot — {@link OptionValues} promises a value read mid-frame stays fixed for the rest of it, so
     * every pass across every stage this frame shares the one snapshot taken here, not a live read. The
     * store's values are immutable and replaced wholesale on a write, so this is a reference read, not a
     * copy.
     */
    public void beginFrame() {
        frameIndex++;
        gpuUse = null;
        worldTlas = 0L;
        worldViewProjection.identity();
        // Restart the post chain at the reconstruction: participation is decided per frame, inside each
        // pass's record(), so last frame's end state says nothing about this one's.
        sceneColor = reconstructedColor;
        nextSceneColorTarget = 0;
        if (optionsStore != null) {
            frameOptionsSnapshot = optionsStore.snapshot();
        }
    }

    /** Publish immutable world-frame inputs before any pass records this frame. */
    public void beginFrame(GpuFrameUse gpuUse, long worldTlas, Matrix4fc worldViewProjection) {
        beginFrame();
        this.gpuUse = Objects.requireNonNull(gpuUse, "gpuUse");
        this.worldTlas = worldTlas;
        this.worldViewProjection.set(worldViewProjection);
    }

    /**
     * The scene image the display map should read: the last thing the post chain wrote, or the
     * reconstructed colour itself when no pass joined the chain this frame.
     */
    public GpuImage sceneColor() {
        return sceneColor;
    }

    /** Every world resource a pass has published, by the name its own Slang declared. */
    public Map<String, WorldResource> worldResources() {
        return Map.copyOf(worldResources);
    }

    /**
     * Changes whenever the published world-resource set does. A pass may publish from {@code record()}
     * — {@link PassFrame#publishWorldResource} exists for exactly that — so the world trace checks this
     * generation after the pre-trace stages and resolves the new descriptor before consuming it.
     */
    public int worldResourceGeneration() {
        return worldResourceGeneration;
    }

    /**
     * Records every active pass in {@code stage}, then a full pipeline barrier so their writes are visible
     * to whatever stage runs next. That barrier and the one after each pass that joined the post chain are
     * the only two the engine inserts on a pass's behalf — both cross a boundary the pass cannot see, since
     * it knows neither what follows its own stage nor that the next pass is about to read what it wrote.
     */
    public void record(RenderStage stage, VkCommandBuffer commandBuffer) {
        Frame frame = new Frame(commandBuffer);
        boolean any = false;
        for (CausticaRenderPass pass : ordered) {
            if (pass.stage() != stage || disabled.contains(pass)) {
                continue;
            }
            any = true;
            frame.currentPass = pass;
            frame.takenTarget = null;
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, commandBuffer, pass.id().toString())) {
                invoke(pass, "record", () -> pass.record(frame));
            }
            // Advance the chain only for a pass that both took a target and survived: a pass disabled
            // mid-record may have written nothing, and handing its target on as scene colour would show
            // whatever the last frame left there.
            if (frame.takenTarget != null && !disabled.contains(pass)) {
                sceneColor = frame.takenTarget;
                nextSceneColorTarget ^= 1;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanBarriers.memoryBarrier(commandBuffer, stack);
                }
            }
        }
        if (any) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanBarriers.memoryBarrier(commandBuffer, stack);
            }
        }
    }

    /** Tell every active pass that the current resource pack is being detached. */
    public void onResourcePackClosing() {
        for (CausticaRenderPass pass : ordered) {
            if (disabled.contains(pass)) {
                continue;
            }
            invoke(pass, "resource-pack closing", pass::onResourcePackClosing);
        }
    }

    /** Tell every active pass that a replacement resource pack is ready. */
    public void onResourcePackApplied() {
        for (CausticaRenderPass pass : ordered) {
            if (disabled.contains(pass)) {
                continue;
            }
            invoke(pass, "resource-pack applied", pass::onResourcePackApplied);
        }
    }

    /** Tell every active pass that the render session changed worlds. */
    public void onWorldChanged() {
        for (CausticaRenderPass pass : ordered) {
            if (disabled.contains(pass)) {
                continue;
            }
            invoke(pass, "world changed", pass::onWorldChanged);
        }
    }

    public void destroy() {
        for (CausticaRenderPass pass : ordered) {
            destroyPass(pass, "shutdown");
        }
    }

    private void invoke(CausticaRenderPass pass, String phase, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            disabled.add(pass);
            CausticaMod.LOGGER.error("Caustica render pass {} failed in {} and was disabled", pass.id(), phase, t);
            // A partial create()/resize() may have already published a slot before throwing; drop it so a
            // later reader can't resolve an image that destroy() is about to free out from under it.
            unpublish(pass);
            destroyPass(pass, "cleanup after " + phase + " failure");
        }
    }

    private void destroyPass(CausticaRenderPass pass, String phase) {
        if (!destroyed.add(pass)) {
            return;
        }
        try {
            pass.destroy();
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Caustica render pass {} failed during {}", pass.id(), phase, t);
        }
    }

    private void publishWorldResourceInternal(CausticaRenderPass owner, String name, WorldResource resource) {
        Objects.requireNonNull(name, "name");
        CausticaRenderPass existing = worldResourcePublishers.putIfAbsent(name, owner);
        if (existing != null && existing != owner) {
            throw new IllegalStateException("multiple render passes publish world resource '" + name
                    + "': " + existing.id() + " and " + owner.id());
        }
        worldResources.put(name, resource);
        worldResourceGeneration++;
    }

    private void unpublish(CausticaRenderPass pass) {
        worldResourcePublishers.entrySet().removeIf(entry -> {
            if (entry.getValue() != pass) {
                return false;
            }
            worldResources.remove(entry.getKey());
            worldResourceGeneration++;
            return true;
        });
    }

    /** Stage order first; Java's stable sort preserves registration order within a stage. */
    static List<CausticaRenderPass> orderPasses(java.util.Collection<CausticaRenderPass> passes) {
        List<CausticaRenderPass> byStage = new ArrayList<>(passes);
        byStage.sort(Comparator.comparing(pass -> pass.stage().ordinal()));
        return byStage;
    }

    private final class Setup implements PassSetup {
        private final CausticaRenderPass owner;

        private Setup(CausticaRenderPass owner) {
            this.owner = owner;
        }

        @Override
        public GpuContext device() {
            return ctx;
        }

        @Override
        public int displayWidth() {
            return displayWidth;
        }

        @Override
        public int displayHeight() {
            return displayHeight;
        }

        @Override
        public void publishWorldResource(String name, GpuImage image, long resourceSampler) {
            Objects.requireNonNull(image, "image");
            publishWorldResourceInternal(name, WorldResource.ofImage(image, resourceSampler));
        }

        @Override
        public void publishWorldResource(String name, GpuBuffer buffer) {
            Objects.requireNonNull(buffer, "buffer");
            publishWorldResourceInternal(name, WorldResource.ofBuffer(buffer));
        }

        private void publishWorldResourceInternal(String name, WorldResource resource) {
            RenderPassManager.this.publishWorldResourceInternal(owner, name, resource);
        }

        @Override
        public OptionValues options() {
            if (optionsStore == null) {
                return DECLARED_DEFAULTS;
            }
            Feature feature = passFeature.get(owner.id());
            return feature != null ? optionsStore.options(feature.id()) : DECLARED_DEFAULTS;
        }
    }

    /**
     * The degenerate view used only by the package-private test constructor, which has no store. It
     * answers with each {@link Option}'s own declared default rather than a caller-supplied fallback, so
     * there is no second copy of a default anywhere for the declaration to drift from.
     */
    private static final OptionValues DECLARED_DEFAULTS = new OptionValues() {
        @Override
        public <T> T get(Option<T> option) {
            return option.defaultValue();
        }
    };

    private final class Frame implements PassFrame {
        private final VkCommandBuffer commandBuffer;
        private CausticaRenderPass currentPass;
        /** The chain target the pass being recorded took, or null if it did not join the chain. */
        private GpuImage takenTarget;

        private Frame(VkCommandBuffer commandBuffer) {
            this.commandBuffer = commandBuffer;
        }

        @Override
        public VkCommandBuffer commandBuffer() {
            return commandBuffer;
        }

        @Override
        public GpuDevice device() {
            return ctx;
        }

        @Override
        public GpuFrameUse gpuUse() {
            if (gpuUse == null) throw new IllegalStateException("GPU frame use is not available");
            return gpuUse;
        }

        @Override
        public long worldTlas() {
            return worldTlas;
        }

        @Override
        public Matrix4fc worldViewProjection() {
            return worldViewProjection;
        }

        @Override
        public long frameIndex() {
            return frameIndex;
        }

        @Override
        public int textureIndex(ResourceId source, SceneMesh.TextureReference texture) {
            return textureResolver.apply(source, texture);
        }

        @Override
        public int displayWidth() {
            return displayWidth;
        }

        @Override
        public int displayHeight() {
            return displayHeight;
        }

        @Override
        public GpuImage sceneColor() {
            if (RenderPassManager.this.sceneColor == null) {
                throw new IllegalStateException("scene colour not set yet this frame");
            }
            return RenderPassManager.this.sceneColor;
        }

        @Override
        public GpuImage sceneColorTarget() {
            if (takenTarget != null) {
                return takenTarget;
            }
            GpuImage target = sceneColorTargets[nextSceneColorTarget];
            if (target == null) {
                throw new IllegalStateException("post chain targets not set yet this frame");
            }
            takenTarget = target;
            return target;
        }

        @Override
        public GpuImage exposureImage() {
            if (RenderPassManager.this.exposureImage == null) {
                throw new IllegalStateException("exposureImage not set yet this frame");
            }
            return RenderPassManager.this.exposureImage;
        }

        @Override
        public OptionValues options() {
            if (currentPass == null || optionsStore == null) {
                return DECLARED_DEFAULTS;
            }
            Feature feature = passFeature.get(currentPass.id());
            return feature != null
                    ? optionsStore.view(feature.id(), frameOptionsSnapshot) : DECLARED_DEFAULTS;
        }

        @Override
        public void publishWorldResource(String name, long imageView, long sampler) {
            if (currentPass == null) {
                throw new IllegalStateException("publishWorldResource called outside a pass's record()");
            }
            publishWorldResourceInternal(currentPass, name, WorldResource.ofRawView(imageView, sampler));
        }

        @Override
        public void memoryBarrier() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanBarriers.memoryBarrier(commandBuffer, stack);
            }
        }
    }

}
