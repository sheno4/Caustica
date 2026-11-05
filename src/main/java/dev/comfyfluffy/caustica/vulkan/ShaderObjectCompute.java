package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.rt.RtContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.lwjgl.vulkan.VK10.*;

/** Compute pipeline with explicit set-zero bindings and immutable invocation descriptor sets. */
public final class ShaderObjectCompute implements AutoCloseable {
    public record Binding(String name, int slot, int type) { }
    private final GpuDevice gpu;
    private final List<Binding> bindings;
    private final int pushBytes;
    private final long setLayout, pipelineLayout, pipeline;
    private final ResourceLifetime lifetime;
    private final Map<Integer, Integer> descriptorCounts;
    private final ArrayDeque<DescriptorAllocation> freeBindings = new ArrayDeque<>();
    private volatile boolean closed;
    private int activeBinds;

    private ShaderObjectCompute(GpuDevice gpu, List<Binding> bindings, int pushBytes,
                                long setLayout, long pipelineLayout, long pipeline) {
        this.gpu = gpu;
        this.bindings = List.copyOf(bindings);
        this.pushBytes = pushBytes;
        this.setLayout = setLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        var counts = new LinkedHashMap<Integer, Integer>();
        bindings.forEach(binding -> counts.merge(binding.type(), 1, Integer::sum));
        descriptorCounts = Map.copyOf(counts);
        lifetime = new ResourceLifetime(() -> vkDestroyPipeline(gpu.vk(), pipeline, null),
                () -> vkDestroyPipelineLayout(gpu.vk(), pipelineLayout, null),
                () -> vkDestroyDescriptorSetLayout(gpu.vk(), setLayout, null));
    }

    public static ShaderObjectCompute load(GpuDevice gpu, Class<?> owner, String resource) {
        List<Binding> bindings;
        int pushBytes;
        if (resource.endsWith("color_bridge.comp.spv")) {
            bindings = List.of(new Binding("0", 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                    new Binding("1", 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
            pushBytes = 16;
        } else if (resource.endsWith("native_inputs.comp.spv")) {
            bindings = List.of(new Binding("0", 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                    new Binding("1", 1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
                    new Binding("2", 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new Binding("3", 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new Binding("4", 4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
            pushBytes = 32;
        } else throw new IllegalArgumentException("Explicit descriptor metadata is required for " + resource);
        try (var input = owner.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing SPIR-V resource " + resource);
            byte[] code = input.readAllBytes();
            ByteBuffer bytes = MemoryUtil.memAlloc(code.length);
            try { bytes.put(code).flip(); return create(gpu, bytes, "main", pushBytes, bindings); }
            finally { MemoryUtil.memFree(bytes); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    public static ShaderObjectCompute create(GpuDevice gpu, ByteBuffer code, String entry,
                                              int pushBytes, List<Binding> bindings) {
        if (!code.isDirect() || code.remaining() < 20 || (code.remaining() & 3) != 0
                || code.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(code.position()) != 0x07230203) {
            throw new IllegalArgumentException("A complete direct SPIR-V module is required");
        }
        if (pushBytes <= 0 || (pushBytes & 3) != 0) throw new IllegalArgumentException("Invalid push ABI");
        long set = 0, layout = 0, module = 0, pipeline = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var nativeBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            var occupied = new HashSet<Integer>();
            for (int i = 0; i < bindings.size(); i++) {
                Binding binding = bindings.get(i);
                if (binding.slot() < 0 || !occupied.add(binding.slot())) throw new IllegalArgumentException("Repeated descriptor binding");
                nativeBindings.get(i).binding(binding.slot()).descriptorType(binding.type()).descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            var out = stack.mallocLong(1);
            RtContext.check(vkCreateDescriptorSetLayout(gpu.vk(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(nativeBindings), null, out), "vkCreateDescriptorSetLayout(Cloudly)");
            set = out.get(0);
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            RtContext.check(vkCreatePipelineLayout(gpu.vk(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(set)).pPushConstantRanges(push), null, out), "vkCreatePipelineLayout(Cloudly)");
            layout = out.get(0);
            RtContext.check(vkCreateShaderModule(gpu.vk(), VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out),
                    "vkCreateShaderModule(Cloudly)");
            module = out.get(0);
            var create = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout);
            var subgroup = VkPipelineShaderStageRequiredSubgroupSizeCreateInfoEXT.calloc(stack).sType$Default()
                    .requiredSubgroupSize(32);
            create.stage().sType$Default().pNext(subgroup.address()).stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8(entry));
            RtContext.check(vkCreateComputePipelines(gpu.vk(), 0L, create, null, out), "vkCreateComputePipelines(Cloudly)");
            pipeline = out.get(0);
            return new ShaderObjectCompute(gpu, bindings, pushBytes, set, layout, pipeline);
        } catch (RuntimeException | Error failure) {
            if (pipeline != 0) vkDestroyPipeline(gpu.vk(), pipeline, null);
            if (layout != 0) vkDestroyPipelineLayout(gpu.vk(), layout, null);
            if (set != 0) vkDestroyDescriptorSetLayout(gpu.vk(), set, null);
            throw failure;
        } finally { if (module != 0) vkDestroyShaderModule(gpu.vk(), module, null); }
    }

    /** Invocation sets become reusable only after their exact GPU retirement closes the binding handle. */
    public BindingSet bind(Map<String, ? extends GpuDescriptorIndex> indices) {
        if (indices.size() != bindings.size()) throw new IllegalArgumentException("Wrong Cloudly descriptor table size");
        DescriptorAllocation allocation;
        synchronized (freeBindings) {
            if (closed) throw new IllegalStateException("Cloudly compute shader is closed");
            activeBinds++;
            allocation = freeBindings.pollFirst();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (allocation == null) allocation = createDescriptorAllocation(stack);
            var writes = VkWriteDescriptorSet.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                Binding binding = bindings.get(i);
                GpuDescriptorIndex index = indices.get(binding.name());
                if (index == null) throw new IllegalArgumentException("Missing Cloudly binding " + binding.name());
                var resource = gpu.descriptorHeap().resolve(index);
                if (resource.type() != binding.type()) throw new IllegalArgumentException("Cloudly binding type mismatch " + binding.name()
                        + ": expected " + binding.type() + ", got " + resource.type());
                var write = writes.get(i).sType$Default().dstSet(allocation.set()).dstBinding(binding.slot())
                        .descriptorType(binding.type()).descriptorCount(1);
                if (resource.imageView() != 0 || resource.sampler() != 0) {
                    write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(resource.imageView())
                            .imageLayout(resource.layout()).sampler(resource.sampler()));
                } else if (resource.bufferView() != 0) write.pTexelBufferView(stack.longs(resource.bufferView()));
                else write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(resource.buffer())
                        .offset(resource.offset()).range(resource.byteSize()));
            }
            vkUpdateDescriptorSets(gpu.vk(), writes, null);
            return new BindingSet(this, allocation);
        } catch (RuntimeException | Error failure) {
            DescriptorAllocation acquired = allocation;
            if (acquired != null) ResourceLifetime.closeAfterFailure(failure, () -> recycle(acquired));
            throw failure;
        } finally {
            // An allocation outside the cache lock may still be consuming the descriptor-set layout.
            boolean releasePipeline;
            synchronized (freeBindings) { releasePipeline = --activeBinds == 0 && closed; }
            if (releasePipeline) lifetime.close();
        }
    }

    private DescriptorAllocation createDescriptorAllocation(MemoryStack stack) {
        long pool = 0;
        try {
            var sizes = VkDescriptorPoolSize.calloc(descriptorCounts.size(), stack);
            int i = 0;
            for (var count : descriptorCounts.entrySet()) sizes.get(i++).type(count.getKey()).descriptorCount(count.getValue());
            var out = stack.mallocLong(1);
            RtContext.check(vkCreateDescriptorPool(gpu.vk(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(sizes), null, out), "vkCreateDescriptorPool(Cloudly invocation)");
            pool = out.get(0);
            RtContext.check(vkAllocateDescriptorSets(gpu.vk(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), out), "vkAllocateDescriptorSets(Cloudly invocation)");
            return new DescriptorAllocation(pool, out.get(0));
        } catch (RuntimeException | Error failure) {
            if (pool != 0) vkDestroyDescriptorPool(gpu.vk(), pool, null);
            throw failure;
        }
    }

    private void recycle(DescriptorAllocation allocation) {
        synchronized (freeBindings) {
            if (!closed) { freeBindings.addLast(allocation); return; }
        }
        vkDestroyDescriptorPool(gpu.vk(), allocation.pool(), null);
    }

    /** Bridge shaders carry local image handles in the first push words. */
    public BindingSet dispatch(VkCommandBuffer commands, ByteBuffer push, int x, int y, int z) {
        Map<String, GpuDescriptorIndex> indices = new LinkedHashMap<>();
        ByteBuffer values = push.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bindings.size(); i++) indices.put(bindings.get(i).name(),
                new GpuDescriptorIndex.Resource(values.getInt(values.position() + i * 4)));
        BindingSet set = bind(indices);
        try { dispatch(commands, push, set, x, y, z); return set; }
        catch (RuntimeException | Error failure) { set.close(); throw failure; }
    }
    public void dispatch(VkCommandBuffer commands, ByteBuffer push, BindingSet set, int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) throw new IllegalArgumentException("Dispatch groups must be positive");
        bindAndPush(commands, push, set);
        vkCmdDispatch(commands, x, y, z);
    }
    public void dispatchIndirect(VkCommandBuffer commands, ByteBuffer push, BindingSet set,
                                 long buffer, long bytes, long offset) {
        if (buffer == 0 || offset < 0 || (offset & 3) != 0 || bytes < 12 || offset > bytes - 12) {
            throw new IllegalArgumentException("Indirect dispatch needs an aligned contained 12-byte command");
        }
        bindAndPush(commands, push, set);
        vkCmdDispatchIndirect(commands, buffer, offset);
    }
    private void bindAndPush(VkCommandBuffer commands, ByteBuffer push, BindingSet set) {
        if (closed || !push.isDirect() || push.remaining() != pushBytes || set.shader != this || set.closed.get()) {
            throw new IllegalArgumentException("Cloudly invocation does not match pipeline ABI");
        }
        vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set.set), null);
        }
        vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
    }
    /** Shader ownership claims must have drained; descriptor handles returned later destroy their pools. */
    @Override public void close() {
        List<DescriptorAllocation> idle;
        boolean releasePipeline;
        synchronized (freeBindings) {
            if (closed) return;
            closed = true;
            idle = List.copyOf(freeBindings);
            freeBindings.clear();
            releasePipeline = activeBinds == 0;
        }
        List<Runnable> destroy = new ArrayList<>(idle.size() + 1);
        for (var allocation : idle) destroy.add(() -> vkDestroyDescriptorPool(gpu.vk(), allocation.pool(), null));
        if (releasePipeline) destroy.add(lifetime::close);
        new ResourceLifetime(destroy.toArray(Runnable[]::new)).close();
    }

    private record DescriptorAllocation(long pool, long set) { }

    public static final class BindingSet implements AutoCloseable {
        private final ShaderObjectCompute shader;
        private final DescriptorAllocation allocation;
        private final long set;
        private final AtomicBoolean closed = new AtomicBoolean();
        private BindingSet(ShaderObjectCompute shader, DescriptorAllocation allocation) {
            this.shader = shader; this.allocation = allocation; this.set = allocation.set();
        }
        /** Return the set only after every GPU use completed or its recording was safely abandoned. */
        @Override public void close() {
            if (closed.compareAndSet(false, true)) shader.recycle(allocation);
        }
    }
}
