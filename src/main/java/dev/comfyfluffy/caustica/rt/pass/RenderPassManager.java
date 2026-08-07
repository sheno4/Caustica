package dev.comfyfluffy.caustica.rt.pass;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassOptions;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Sequences registered {@link CausticaRenderPass}es by stage and drives their lifecycle. Each pass owns
 * its own Vulkan resources outright — this class does not allocate, track, or barrier anything on a
 * pass's behalf beyond the two engine-produced inputs ({@link #setReconstructedColor}/
 * {@link #setExposureImage}) and the named world-resource registry passes publish into via
 * {@link PassSetup#publishWorldResource}. A pass that throws from any lifecycle method is disabled with a
 * logged error and any world resource it had published is unpublished in the same step, so a later reader
 * never resolves an image the pass's own cleanup is about to free; the frame loop continues without it,
 * matching {@code ProviderManager}'s isolation discipline.
 */
public final class RenderPassManager {
    private final RtContext ctx;
    private final List<CausticaRenderPass> ordered;
    private final Set<CausticaRenderPass> disabled = new HashSet<>();
    private final Map<String, WorldResource> worldResources = new LinkedHashMap<>();
    private final Map<String, CausticaRenderPass> worldResourcePublishers = new LinkedHashMap<>();
    private final Map<String, NamedOutput> outputs = new LinkedHashMap<>();
    private final Map<String, CausticaRenderPass> outputPublishers = new LinkedHashMap<>();
    private GpuImage reconstructedColor;
    private GpuImage exposureImage;
    private final long sampler;
    private int displayWidth;
    private int displayHeight;
    private long frameIndex = -1;

    /**
     * A pass-published world resource: exactly one of an image (with the sampler it should be read with)
     * or a buffer, matching whichever {@code publishWorldResource} overload a pass called — driven by
     * whichever descriptor kind that pass's own Slang declared (see
     * {@code WorldShaderCompiler.PassResourceKind}).
     */
    public record WorldResource(GpuImage image, long sampler, GpuBuffer buffer) {
        public WorldResource {
            if ((image == null) == (buffer == null)) {
                throw new IllegalArgumentException("a world resource is exactly one of an image or a buffer");
            }
        }

        static WorldResource ofImage(GpuImage image, long sampler) {
            return new WorldResource(image, sampler, null);
        }

        static WorldResource ofBuffer(GpuBuffer buffer) {
            return new WorldResource(null, 0L, buffer);
        }
    }

    /** A pass-published output another pipeline reads directly: see {@link PassSetup#publishOutput}. */
    public record NamedOutput(GpuImage image, int levelCount) {
    }

    RenderPassManager(RtContext ctx, List<CausticaRenderPass> ordered, long sampler) {
        this.ctx = ctx;
        this.ordered = ordered;
        this.sampler = sampler;
    }

    public static RenderPassManager create(RtContext ctx, Map<Identifier, CausticaRenderPass> registered) {
        List<CausticaRenderPass> ordered = orderPasses(registered.values());
        long sampler = createSampler(ctx);
        RenderPassManager manager = new RenderPassManager(ctx, ordered, sampler);
        for (CausticaRenderPass pass : ordered) {
            PassSetup setup = manager.new Setup(pass);
            manager.invoke(pass, "create", () -> pass.create(setup));
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

    /** Engine-produced input: this frame's scalar exposure value, set once per frame before recording. */
    public void setExposureImage(GpuImage image) {
        exposureImage = image;
    }

    /** Reset per-frame bookkeeping (currently just {@link #frameIndex}); call once before any recording. */
    public void beginFrame() {
        frameIndex++;
    }

    /** Every world resource a pass has published, by the name its own Slang declared. */
    public Map<String, WorldResource> worldResources() {
        return Map.copyOf(worldResources);
    }

    public GpuImage output(String name) {
        NamedOutput output = outputs.get(name);
        if (output == null) {
            throw new IllegalStateException("no render pass has published output '" + name + "'");
        }
        return output.image();
    }

    public boolean hasOutput(String name) {
        return outputs.containsKey(name);
    }

    public int outputLevelCount(String name) {
        NamedOutput output = outputs.get(name);
        if (output == null) {
            throw new IllegalStateException("no render pass has published output '" + name + "'");
        }
        return output.levelCount();
    }

    public long sampler() {
        return sampler;
    }

    /**
     * Records every active pass in {@code stage}, then a full pipeline barrier so their writes are visible
     * to whatever stage runs next — the one barrier the engine inserts on a pass's behalf, since a pass
     * has no way to know what follows its own stage.
     */
    public void record(RenderStage stage, VkCommandBuffer commandBuffer) {
        PassFrame frame = new Frame(commandBuffer);
        boolean any = false;
        for (CausticaRenderPass pass : ordered) {
            if (pass.stage() != stage || disabled.contains(pass)) {
                continue;
            }
            any = true;
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, commandBuffer, pass.id().toString())) {
                invoke(pass, "record", () -> pass.record(frame));
            }
        }
        if (any) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            }
        }
    }

    /** Tell every active pass its persistent bake state (if any) should be considered stale. */
    public void invalidate() {
        for (CausticaRenderPass pass : ordered) {
            if (disabled.contains(pass)) {
                continue;
            }
            invoke(pass, "invalidate", pass::invalidate);
        }
    }

    public void destroy() {
        for (CausticaRenderPass pass : ordered) {
            try {
                pass.destroy();
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Caustica render pass {} failed during shutdown", pass.id(), t);
            }
        }
        VK10.vkDestroySampler(ctx.vk(), sampler, null);
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
            try {
                pass.destroy();
            } catch (Throwable cleanupFailure) {
                CausticaMod.LOGGER.error("Caustica render pass {} cleanup after failure also failed",
                        pass.id(), cleanupFailure);
            }
        }
    }

    private void unpublish(CausticaRenderPass pass) {
        worldResourcePublishers.entrySet().removeIf(entry -> {
            if (entry.getValue() != pass) {
                return false;
            }
            worldResources.remove(entry.getKey());
            return true;
        });
        outputPublishers.entrySet().removeIf(entry -> {
            if (entry.getValue() != pass) {
                return false;
            }
            outputs.remove(entry.getKey());
            return true;
        });
    }

    /** Stage order first, then a topological sort of {@link CausticaRenderPass#after()} within a stage. */
    static List<CausticaRenderPass> orderPasses(java.util.Collection<CausticaRenderPass> passes) {
        List<CausticaRenderPass> byStage = new ArrayList<>(passes);
        byStage.sort(Comparator.comparing(pass -> pass.stage().ordinal()));
        List<CausticaRenderPass> result = new ArrayList<>();
        int index = 0;
        while (index < byStage.size()) {
            RenderStage stage = byStage.get(index).stage();
            int end = index;
            while (end < byStage.size() && byStage.get(end).stage() == stage) {
                end++;
            }
            result.addAll(topoSortStage(byStage.subList(index, end)));
            index = end;
        }
        return result;
    }

    private static List<CausticaRenderPass> topoSortStage(List<CausticaRenderPass> stagePasses) {
        Map<Identifier, CausticaRenderPass> byId = new LinkedHashMap<>();
        for (CausticaRenderPass pass : stagePasses) {
            byId.put(pass.id(), pass);
        }
        Map<Identifier, Integer> remainingDeps = new LinkedHashMap<>();
        Map<Identifier, List<Identifier>> dependents = new LinkedHashMap<>();
        for (CausticaRenderPass pass : stagePasses) {
            List<Identifier> afterHere = pass.after().stream().filter(byId::containsKey).toList();
            remainingDeps.put(pass.id(), afterHere.size());
            for (Identifier dependency : afterHere) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(pass.id());
            }
        }
        TreeSet<Identifier> ready = new TreeSet<>(Comparator.comparing(Identifier::toString));
        remainingDeps.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<CausticaRenderPass> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            Identifier next = ready.pollFirst();
            result.add(byId.get(next));
            for (Identifier dependent : dependents.getOrDefault(next, List.of())) {
                int left = remainingDeps.merge(dependent, -1, Integer::sum);
                if (left == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (result.size() != stagePasses.size()) {
            Set<Identifier> cyclic = new HashSet<>(byId.keySet());
            result.forEach(pass -> cyclic.remove(pass.id()));
            throw new IllegalStateException("render pass ordering cycle involving " + cyclic);
        }
        return result;
    }

    private static long createSampler(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateSampler(ctx.vk(), info, null, handle), "vkCreateSampler(render passes)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, handle.get(0),
                    "render-pass consumer linear clamp sampler");
            return handle.get(0);
        }
    }

    private final class Setup implements PassSetup {
        private final CausticaRenderPass owner;

        private Setup(CausticaRenderPass owner) {
            this.owner = owner;
        }

        @Override
        public RtContext context() {
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
            Objects.requireNonNull(name, "name");
            CausticaRenderPass existing = worldResourcePublishers.putIfAbsent(name, owner);
            if (existing != null && existing != owner) {
                throw new IllegalStateException("multiple render passes publish world resource '" + name
                        + "': " + existing.id() + " and " + owner.id());
            }
            worldResources.put(name, resource);
        }

        @Override
        public void publishOutput(String name, GpuImage image, int levelCount) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(image, "image");
            CausticaRenderPass existing = outputPublishers.putIfAbsent(name, owner);
            if (existing != null && existing != owner) {
                throw new IllegalStateException("multiple render passes publish output '" + name + "': "
                        + existing.id() + " and " + owner.id());
            }
            outputs.put(name, new NamedOutput(image, levelCount));
        }
    }

    private static final NoopOptions NOOP_OPTIONS = new NoopOptions();

    private final class Frame implements PassFrame {
        private final VkCommandBuffer commandBuffer;

        private Frame(VkCommandBuffer commandBuffer) {
            this.commandBuffer = commandBuffer;
        }

        @Override
        public VkCommandBuffer commandBuffer() {
            return commandBuffer;
        }

        @Override
        public long frameIndex() {
            return frameIndex;
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
        public GpuImage reconstructedColor() {
            if (RenderPassManager.this.reconstructedColor == null) {
                throw new IllegalStateException("reconstructedColor not set yet this frame");
            }
            return RenderPassManager.this.reconstructedColor;
        }

        @Override
        public GpuImage exposureImage() {
            if (RenderPassManager.this.exposureImage == null) {
                throw new IllegalStateException("exposureImage not set yet this frame");
            }
            return RenderPassManager.this.exposureImage;
        }

        @Override
        public PassOptions options() {
            return NOOP_OPTIONS;
        }

        @Override
        public void memoryBarrier() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            }
        }
    }

    private static final class NoopOptions implements PassOptions {
        @Override
        public <T> T get(String optionId, T fallback) {
            return fallback;
        }
    }
}
