package dev.comfyfluffy.caustica.rt.pass;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.EngineImage;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassOptions;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Sequences registered {@link CausticaRenderPass}es by stage and drives their lifecycle. Each pass owns
 * its own Vulkan resources outright — this class does not allocate, track, or barrier anything on a
 * pass's behalf beyond the fixed {@link EngineImage} slots. A pass that throws from any lifecycle method
 * is disabled with a logged error and any slot it had published is unpublished in the same step, so a
 * later reader never resolves an image the pass's own cleanup is about to free; the frame loop continues
 * without it, matching {@code ProviderManager}'s isolation discipline.
 */
public final class RenderPassManager {
    private final RtContext ctx;
    private final List<CausticaRenderPass> ordered;
    private final Set<CausticaRenderPass> disabled = new HashSet<>();
    private final Map<EngineImage, GpuImage> engineImages = new EnumMap<>(EngineImage.class);
    private final Map<EngineImage, Integer> engineImageLevels = new EnumMap<>(EngineImage.class);
    private final Map<EngineImage, CausticaRenderPass> publishers = new EnumMap<>(EngineImage.class);
    private final long sampler;
    private int displayWidth;
    private int displayHeight;
    private long frameIndex = -1;

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

    public void setExternalImage(EngineImage slot, GpuImage image) {
        engineImages.put(slot, image);
        engineImageLevels.put(slot, 1);
    }

    /** Reset per-frame bookkeeping (currently just {@link #frameIndex}); call once before any recording. */
    public void beginFrame() {
        frameIndex++;
    }

    public GpuImage image(EngineImage slot) {
        GpuImage image = engineImages.get(slot);
        if (image == null) {
            throw new IllegalStateException(slot + " has no bound image");
        }
        return image;
    }

    public boolean hasImage(EngineImage slot) {
        return engineImages.containsKey(slot);
    }

    public int levelCount(EngineImage slot) {
        Integer levels = engineImageLevels.get(slot);
        if (levels == null) {
            throw new IllegalStateException(slot + " has no bound image");
        }
        return levels;
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
        publishers.entrySet().removeIf(entry -> {
            if (entry.getValue() != pass) {
                return false;
            }
            engineImages.remove(entry.getKey());
            engineImageLevels.remove(entry.getKey());
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
        public void publish(EngineImage slot, GpuImage image, int levelCount) {
            if (!slot.passOutput()) {
                throw new IllegalArgumentException(slot + " is engine-produced and cannot be published by a pass");
            }
            CausticaRenderPass existing = publishers.putIfAbsent(slot, owner);
            if (existing != null && existing != owner) {
                throw new IllegalStateException("multiple render passes publish " + slot + ": "
                        + existing.id() + " and " + owner.id());
            }
            engineImages.put(slot, image);
            engineImageLevels.put(slot, levelCount);
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
        public GpuImage engineImage(EngineImage slot) {
            return image(slot);
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
