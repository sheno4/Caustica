/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.vulkan;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.EXTSubgroupSizeControl.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;

/** A private Vulkan compute device, matched to the host OpenGL device by UUID. */
public final class CloudlyDevice implements AutoCloseable {
    private static final List<String> EXTENSIONS = List.of(
            VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
            VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
            VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
            VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);

    private final Object queueLock = new Object();
    private final Set<Submission> pending = ConcurrentHashMap.newKeySet();
    private final Set<SharedImage> images = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Cloudly Vulkan bake");
        thread.setDaemon(true);
        return thread;
    });
    private VkInstance instance;
    private VkPhysicalDevice physical;
    private VkDevice device;
    private VkQueue queue;
    private int queueFamily;
    private long allocator;
    private ExternalSemaphore inputReady;
    private ExternalSemaphore outputReady;
    private volatile boolean closing;
    private boolean closed;

    /** Requires the 16-byte GL_DEVICE_UUID_EXT of the current OpenGL context. */
    public CloudlyDevice(byte[] glDeviceUuid) {
        if (glDeviceUuid.length != VK_UUID_SIZE) {
            throw new IllegalArgumentException("OpenGL device UUID must contain 16 bytes");
        }
        try {
            createInstance();
            selectPhysicalDevice(glDeviceUuid);
            createDevice();
            createAllocator();
            inputReady = createExternalSemaphore();
            outputReady = createExternalSemaphore();
        } catch (Throwable failure) {
            try {
                closeNative();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            worker.shutdownNow();
            throw failure;
        }
    }

    public VkDevice vk() { return device; }
    public long vmaAllocator() { return allocator; }
    public int queueFamily() { return queueFamily; }
    public int[] asyncBufferSharingQueueFamilies() { return new int[] { queueFamily }; }
    public long inputReadyHandle() { return inputReady.win32Handle; }
    public long outputReadyHandle() { return outputReady.win32Handle; }

    /**
     * Creates dedicated, exportable optimal-tiled storage. GL imports the returned handle as
     * GL_HANDLE_TYPE_OPAQUE_WIN32_EXT with GL_DEDICATED_MEMORY_OBJECT_EXT enabled.
     * Delete the importing GL texture and memory object before closing the image.
     */
    public synchronized SharedImage createSharedImage(int width, int height, int format, int usage) {
        ensureOpen();
        SharedImage image = SharedImage.create(this, physical, width, height, format, usage);
        try {
            try (Submission initial = recordAndSubmit(command -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
                    barrier.get(0).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .srcQueueFamilyIndex(queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL)
                            .image(image.image()).subresourceRange(range -> range
                                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier);
                }
            }, false)) {
                // The initial Vulkan-to-GL handoff completes before GL can import and use storage.
            }
            images.add(image);
            return image;
        } catch (Throwable failure) {
            image.close();
            throw failure;
        }
    }

    /** Runs command recording on a CPU worker and calls completion after its GPU fence. */
    public synchronized void submit(Consumer<VkCommandBuffer> recorder, Consumer<Throwable> completion) {
        ensureOpen();
        worker.execute(() -> {
            Throwable failure = null;
            try (Submission submission = recordAndSubmit(recorder, false)) {
                // Fence retirement bounds the lifetime of resources captured by the recorder.
            } catch (Throwable thrown) {
                failure = thrown;
            }
            completion.accept(failure);
        });
    }

    /**
     * Submits one GL/Vulkan frame without a host GPU wait. The host first signals inputReady;
     * Vulkan waits it, acquires all images in GENERAL, records compute, releases them back to GL,
     * and signals outputReady. Close the returned submission before reusing frame descriptors.
     */
    public synchronized Submission submitFrame(Consumer<VkCommandBuffer> recorder, SharedImage... sharedImages) {
        ensureOpen();
        if (sharedImages.length == 0) throw new IllegalArgumentException("A shared frame needs images");
        return recordAndSubmit(command -> {
            ownershipBarrier(command, true, sharedImages);
            recorder.accept(command);
            ownershipBarrier(command, false, sharedImages);
        }, true);
    }

    private void ownershipBarrier(VkCommandBuffer command, boolean acquire, SharedImage[] sharedImages) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                    | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            int stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(sharedImages.length, stack);
            for (int index = 0; index < sharedImages.length; index++) {
                SharedImage image = sharedImages[index];
                if (image.owner() != this) {
                    throw new IllegalArgumentException("Shared image belongs to another Vulkan device");
                }
                if (image.image() == 0) throw new IllegalStateException("Shared frame image is closed");
                barriers.get(index).sType$Default()
                        .srcAccessMask(acquire ? 0 : access)
                        .dstAccessMask(acquire ? access : 0)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(acquire ? VK_QUEUE_FAMILY_EXTERNAL : queueFamily)
                        .dstQueueFamilyIndex(acquire ? queueFamily : VK_QUEUE_FAMILY_EXTERNAL)
                        .image(image.image()).subresourceRange(range -> range
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            }
            vkCmdPipelineBarrier(command,
                    acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : stages,
                    acquire ? stages : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0, null, null, barriers);
        }
    }

    private Submission recordAndSubmit(Consumer<VkCommandBuffer> recorder, boolean frame) {
        long pool = 0;
        long fence = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(queueFamily).flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            check(vkCreateCommandPool(device, poolInfo, null, handle), "create command pool");
            pool = handle.get(0);
            PointerBuffer commandPointer = stack.mallocPointer(1);
            var allocation = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            check(vkAllocateCommandBuffers(device, allocation, commandPointer), "allocate command buffer");
            var command = new VkCommandBuffer(commandPointer.get(0), device);
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin command buffer");
            recorder.accept(command);
            check(vkEndCommandBuffer(command), "end command buffer");
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "create submission fence");
            fence = handle.get(0);
            var submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address()));
            if (frame) {
                submit.pWaitSemaphores(stack.longs(inputReady.semaphore))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                        .pSignalSemaphores(stack.longs(outputReady.semaphore));
            }
            synchronized (queueLock) {
                check(vkQueueSubmit(queue, submit, fence), "submit compute commands");
            }
            Submission submission = new Submission(pool, fence);
            pending.add(submission);
            return submission;
        } catch (Throwable failure) {
            if (fence != 0) vkDestroyFence(device, fence, null);
            if (pool != 0) vkDestroyCommandPool(device, pool, null);
            throw failure;
        }
    }

    /** Drains submitted CPU bake work; the caller must stop producing new bake jobs first. */
    public void waitIdle() {
        ensureOpen();
        try {
            worker.submit(() -> {}).get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted draining Cloudly GPU worker", failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Cloudly GPU worker failed", failure.getCause());
        }
        synchronized (queueLock) {
            check(vkQueueWaitIdle(queue), "wait for compute queue");
        }
        for (Submission submission : Set.copyOf(pending)) submission.close();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            if (closing) throw new IllegalStateException("Cloudly Vulkan shutdown is already in progress");
            closing = true;
            worker.shutdown();
        }
        try {
            if (!worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Cloudly GPU worker did not terminate");
            }
            synchronized (queueLock) {
                check(vkQueueWaitIdle(queue), "wait for compute queue shutdown");
            }
            for (Submission submission : Set.copyOf(pending)) submission.close();
            for (SharedImage image : Set.copyOf(images)) image.close();
            try {
                closeNative();
            } finally {
                synchronized (this) {
                    closed = true;
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted stopping Cloudly GPU worker", failure);
        } finally {
            closing = false;
        }
    }

    void forget(SharedImage image) { images.remove(image); }

    private void closeNative() {
        try {
            if (inputReady != null) inputReady.close();
        } finally {
            try {
                if (outputReady != null) outputReady.close();
            } finally {
                if (allocator != 0) {
                    Vma.vmaDestroyAllocator(allocator);
                    allocator = 0;
                }
                if (device != null) {
                    vkDestroyDevice(device, null);
                    device = null;
                }
                if (instance != null) {
                    vkDestroyInstance(instance, null);
                    instance = null;
                }
            }
        }
    }

    private void ensureOpen() {
        if (closing || worker.isShutdown()) throw new IllegalStateException("Cloudly Vulkan device is closing");
    }

    private void createInstance() {
        if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2) {
            throw new IllegalStateException("Cloudly requires Vulkan 1.2");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var application = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("Cloudly standalone compute"))
                    .applicationVersion(1).apiVersion(VK_API_VERSION_1_2);
            var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(create, null, pointer), "create Vulkan instance");
            instance = new VkInstance(pointer.get(0), create);
        }
    }

    private void selectPhysicalDevice(byte[] uuid) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "count physical devices");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate physical devices");
            for (int index = 0; index < devices.capacity(); index++) {
                var candidate = new VkPhysicalDevice(devices.get(index), instance);
                var identity = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                        .pNext(identity.address());
                vkGetPhysicalDeviceProperties2(candidate, properties);
                byte[] candidateUuid = new byte[VK_UUID_SIZE];
                identity.deviceUUID().get(candidateUuid);
                if (Arrays.equals(uuid, candidateUuid)) {
                    if (properties.properties().apiVersion() < VK_API_VERSION_1_2) {
                        throw new IllegalStateException("The OpenGL GPU does not support Vulkan 1.2");
                    }
                    physical = candidate;
                    validateExtensions(stack);
                    validateComputeFeatures(stack);
                    queueFamily = selectComputeQueue(stack);
                    return;
                }
            }
        }
        throw new IllegalStateException("No Vulkan physical device matches OpenGL GL_DEVICE_UUID_EXT");
    }

    private void validateExtensions(MemoryStack stack) {
        var count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null),
                "count device extensions");
        // Driver-sized inventories exceed the bounded per-thread MemoryStack on recent GPUs.
        try (var properties = VkExtensionProperties.malloc(count.get(0))) {
            check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, properties),
                    "enumerate device extensions");
            for (String required : EXTENSIONS) {
                boolean supported = false;
                for (int index = 0; index < count.get(0); index++) {
                    if (required.equals(properties.get(index).extensionNameString())) supported = true;
                }
                if (!supported) throw new IllegalStateException("Cloudly GPU lacks " + required);
            }
        }
        var semaphoreInfo = VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default()
                .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        var semaphoreProperties = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
        vkGetPhysicalDeviceExternalSemaphoreProperties(physical, semaphoreInfo, semaphoreProperties);
        if ((semaphoreProperties.externalSemaphoreFeatures()
                & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) == 0
                || (semaphoreProperties.compatibleHandleTypes()
                & VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT) == 0) {
            throw new IllegalStateException("Cloudly GPU cannot export opaque Win32 semaphores");
        }
    }

    private void validateComputeFeatures(MemoryStack stack) {
        var synchronization = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default();
        var subgroupControl = VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.calloc(stack).sType$Default()
                .pNext(synchronization.address());
        var vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                .pNext(subgroupControl.address());
        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(vulkan12.address());
        vkGetPhysicalDeviceFeatures2(physical, features);
        VkPhysicalDeviceFeatures core = features.features();
        if (!core.shaderInt64() || !core.shaderInt16() || !core.shaderStorageImageExtendedFormats()
                || !core.shaderStorageImageReadWithoutFormat() || !core.shaderStorageImageWriteWithoutFormat()
                || !vulkan12.bufferDeviceAddress() || !vulkan12.shaderFloat16()
                || !subgroupControl.subgroupSizeControl() || !synchronization.synchronization2()) {
            throw new IllegalStateException("Cloudly GPU lacks required shader arithmetic, image, BDA, or subgroup features");
        }
        var size = VkPhysicalDeviceSubgroupSizeControlPropertiesEXT.calloc(stack).sType$Default();
        var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default().pNext(size.address());
        var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(subgroup.address());
        vkGetPhysicalDeviceProperties2(physical, properties);
        int operations = VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT
                | VK_SUBGROUP_FEATURE_BALLOT_BIT | VK_SUBGROUP_FEATURE_QUAD_BIT
                | VK_SUBGROUP_FEATURE_SHUFFLE_BIT | VK_SUBGROUP_FEATURE_VOTE_BIT;
        var limits = properties.properties().limits();
        if ((subgroup.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) == 0
                || (subgroup.supportedOperations() & operations) != operations
                || size.minSubgroupSize() > 32 || size.maxSubgroupSize() < 32
                || (size.requiredSubgroupSizeStages() & VK_SHADER_STAGE_COMPUTE_BIT) == 0
                || size.maxComputeWorkgroupSubgroups() < 32
                || limits.maxComputeWorkGroupInvocations() < 1024
                || limits.maxComputeWorkGroupSize(0) < 1024) {
            throw new IllegalStateException("Cloudly requires 32-lane compute subgroups and 1024-invocation workgroups");
        }
    }

    private int selectComputeQueue(MemoryStack stack) {
        var count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
        var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
        int fallback = -1;
        for (int index = 0; index < families.capacity(); index++) {
            int flags = families.get(index).queueFlags();
            if ((flags & VK_QUEUE_COMPUTE_BIT) != 0 && families.get(index).queueCount() != 0) {
                if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0) return index;
                fallback = index;
            }
        }
        if (fallback < 0) throw new IllegalStateException("Cloudly GPU has no compute queue");
        return fallback;
    }

    private void createDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var synchronization = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default()
                    .synchronization2(true);
            var subgroup = VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.calloc(stack).sType$Default()
                    .pNext(synchronization.address()).subgroupSizeControl(true);
            var vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                    .pNext(subgroup.address()).bufferDeviceAddress(true).shaderFloat16(true);
            var features = VkPhysicalDeviceFeatures.calloc(stack).shaderInt64(true).shaderInt16(true)
                    .shaderStorageImageExtendedFormats(true).shaderStorageImageReadWithoutFormat(true)
                    .shaderStorageImageWriteWithoutFormat(true);
            var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1));
            PointerBuffer extensionNames = stack.mallocPointer(EXTENSIONS.size());
            for (String extension : EXTENSIONS) extensionNames.put(stack.UTF8(extension));
            extensionNames.flip();
            var create = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(vulkan12.address())
                    .pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(extensionNames).pEnabledFeatures(features);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateDevice(physical, create, null, pointer), "create compute device");
            device = new VkDevice(pointer.get(0), physical, create);
            vkGetDeviceQueue(device, queueFamily, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
        }
    }

    private void createAllocator() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
            var create = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .physicalDevice(physical).device(device).instance(instance)
                    .vulkanApiVersion(VK_API_VERSION_1_2).pVulkanFunctions(functions);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(Vma.vmaCreateAllocator(create, pointer), "create VMA allocator");
            allocator = pointer.get(0);
        }
    }

    private ExternalSemaphore createExternalSemaphore() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var create = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address());
            var pointer = stack.mallocLong(1);
            check(vkCreateSemaphore(device, create, null, pointer), "create external semaphore");
            long semaphore = pointer.get(0);
            try {
                PointerBuffer handle = stack.mallocPointer(1);
                var get = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .semaphore(semaphore).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
                check(vkGetSemaphoreWin32HandleKHR(device, get, handle), "export semaphore Win32 handle");
                return new ExternalSemaphore(semaphore, handle.get(0));
            } catch (Throwable failure) {
                vkDestroySemaphore(device, semaphore, null);
                throw failure;
            }
        }
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }

    private final class ExternalSemaphore implements AutoCloseable {
        private long semaphore;
        private long win32Handle;
        private ExternalSemaphore(long semaphore, long win32Handle) {
            this.semaphore = semaphore;
            this.win32Handle = win32Handle;
        }
        @Override public void close() {
            if (semaphore == 0) return;
            try {
                Win32Handles.close(win32Handle);
            } finally {
                vkDestroySemaphore(device, semaphore, null);
                semaphore = 0;
                win32Handle = 0;
            }
        }
    }

    /** Owns the command pool and fence until GPU execution has completed. */
    public final class Submission implements AutoCloseable {
        private long pool;
        private long fence;
        private Submission(long pool, long fence) {
            this.pool = pool;
            this.fence = fence;
        }
        @Override public synchronized void close() {
            if (fence == 0) return;
            try {
                check(vkWaitForFences(device, fence, true, -1L), "wait for compute fence");
            } finally {
                vkDestroyFence(device, fence, null);
                vkDestroyCommandPool(device, pool, null);
                fence = 0;
                pool = 0;
                pending.remove(this);
            }
        }
    }
}
