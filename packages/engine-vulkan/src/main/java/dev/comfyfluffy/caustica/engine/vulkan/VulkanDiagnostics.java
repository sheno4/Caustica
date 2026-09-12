package dev.comfyfluffy.caustica.engine.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.NVDeviceDiagnosticCheckpoints;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceFaultAddressInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultCountsEXT;
import org.lwjgl.vulkan.VkDeviceFaultInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultVendorInfoEXT;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties2;
import org.lwjgl.vulkan.VkCheckpointDataNV;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** Startup Vulkan inventory and best-effort {@code VK_EXT_device_fault} reporting. */
public final class VulkanDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanDiagnostics.class);
    private static final int MAX_QUEUE_CHECKPOINTS = 64;
    private static final AtomicBoolean FAULT_REPORTED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, String> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentSkipListMap<VulkanDeviceAddress, BufferRange> BUFFERS =
            new ConcurrentSkipListMap<>((left, right) -> Long.compareUnsigned(left.value(), right.value()));
    private static volatile boolean deviceFaultRequested;
    private static volatile boolean deviceFaultEnabled;
    private static int memoryHeapCount;
    private static volatile long allocator;
    private static boolean startupLogged;
    private static boolean instanceLayersLogged;

    public record StartupInfo(String deviceName, String vendorName, String deviceType,
                              String driverName, String driverInfo, int driverId,
                              String deviceUuid, String driverUuid, String conformance,
                              String selectedQueues) {
    }

    private record BufferRange(VulkanDeviceAddressRange bytes, long handle, String label) {
        boolean contains(VulkanDeviceAddress value) {
            long address = bytes.address().value();
            return Long.compareUnsigned(value.value(), address) >= 0
                    && Long.compareUnsigned(value.value() - address, bytes.byteSize()) < 0;
        }
    }

    private VulkanDiagnostics() {
    }

    /** Publishes the live renderer allocator for device-loss budget reporting. */
    public static void registerAllocator(long vmaAllocator) {
        allocator = vmaAllocator;
    }

    /** Log loader-visible layers and the explicit layer list passed to {@code vkCreateInstance}. */
    public static synchronized void logInstanceLayers(VkInstanceCreateInfo createInfo) {
        if (instanceLayersLogged) {
            return;
        }
        instanceLayersLogged = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer count = stack.callocInt(1);
            int result = VK10.vkEnumerateInstanceLayerProperties(count, null);
            if (result != VK10.VK_SUCCESS) {
                LOGGER.warn("vkEnumerateInstanceLayerProperties(count) failed: {}", result);
            } else {
                VkLayerProperties.Buffer layers = VkLayerProperties.calloc(count.get(0), stack);
                result = VK10.vkEnumerateInstanceLayerProperties(count, layers);
                if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
                    LOGGER.warn("vkEnumerateInstanceLayerProperties(data) failed: {}", result);
                } else {
                    int layerCount = Math.min(count.get(0), layers.capacity());
                    LOGGER.info("Vulkan loader-visible instance layers ({}):", layerCount);
                    for (int i = 0; i < layerCount; i++) {
                        VkLayerProperties layer = layers.get(i);
                        LOGGER.info(
                                "Vulkan instance layer[{}]: name='{}', spec={}, implementation={} (0x{}), description='{}'",
                                i, layer.layerNameString(), VulkanRequiredProfile.formatApiVersion(layer.specVersion()),
                                Integer.toUnsignedLong(layer.implementationVersion()),
                                Integer.toUnsignedString(layer.implementationVersion(), 16),
                                layer.descriptionString());
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to enumerate Vulkan instance layers", t);
        }

        List<String> requested = new ArrayList<>();
        PointerBuffer names = createInfo.ppEnabledLayerNames();
        if (names != null) {
            for (int i = names.position(); i < names.limit(); i++) {
                requested.add(MemoryUtil.memUTF8(names.get(i)));
            }
        }
        LOGGER.info(
                "Vulkan application-requested instance layers ({}): {} (implicit loader layers may still activate)",
                requested.size(), requested.isEmpty() ? "<none>" : requested);

        List<String> loaderEnvironment = new ArrayList<>();
        for (String key : List.of("VK_INSTANCE_LAYERS", "VK_LOADER_LAYERS_ENABLE", "VK_LOADER_LAYERS_DISABLE",
                "VK_LOADER_LAYERS_ALLOW", "VK_LOADER_DEBUG", "SteamAppId", "SteamGameId")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                loaderEnvironment.add(key + "=" + value);
            }
        }
        LOGGER.info("Vulkan layer/overlay environment: {}",
                loaderEnvironment.isEmpty() ? "<none>" : loaderEnvironment);
    }

    /** Publishes whether device-fault reporting was selected during host device negotiation. */
    public static void configureDeviceFault(boolean fault) {
        deviceFaultRequested = fault;
    }

    public static void logEnabledExtensions(Collection<String> extensions) {
        List<String> sorted = new ArrayList<>(extensions);
        sorted.sort(Comparator.naturalOrder());
        LOGGER.info("Vulkan device extensions requested ({}): {}", sorted.size(), sorted);
    }

    /** Verify the entry point after logical-device creation. */
    public static void probe(VkDevice device) {
        deviceFaultEnabled = deviceFaultRequested && device.getCapabilities().vkGetDeviceFaultInfoEXT != 0L;
        if (deviceFaultRequested) {
            LOGGER.info("Vulkan device-fault diagnostics {}",
                    deviceFaultEnabled ? "enabled" : "FAILED: entry point missing");
        }
    }

    public static void setInFlight(String lane, String state) {
        if (state == null) {
            IN_FLIGHT.remove(lane);
        } else {
            IN_FLIGHT.put(lane, state);
        }
    }

    public static void registerBuffer(VulkanDeviceAddressRange bytes, long handle, String label) {
        BUFFERS.put(bytes.address(), new BufferRange(bytes, handle, label));
    }

    public static void unregisterBuffer(VulkanDeviceAddress address, long handle) {
        BUFFERS.computeIfPresent(address, (ignored, range) -> range.handle == handle ? null : range);
    }

    /** Query fault details once, immediately after a device-loss result is observed. */
    public static void reportDeviceLost(VkDevice device, String operation, Map<String, VkQueue> queues) {
        if (!FAULT_REPORTED.compareAndSet(false, true)) {
            return;
        }
        var history = GpuCrashHistory.capture();
        var anomalies = GpuCrashHistory.captureAnomalies();
        try {
            LOGGER.error("Vulkan device lost while {}", operation);
            queues.forEach((label, queue) -> logNvQueueCheckpoints(queue, label));
            logRuntimeSnapshot();
            if (!deviceFaultEnabled || device == null || device.getCapabilities().vkGetDeviceFaultInfoEXT == 0L) {
                LOGGER.error("VK_EXT_device_fault is unavailable; no driver fault details can be queried");
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDeviceFaultCountsEXT counts = VkDeviceFaultCountsEXT.calloc(stack).sType$Default();
                int result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(device, counts, null);
                if (result != VK10.VK_SUCCESS) {
                    LOGGER.error("vkGetDeviceFaultInfoEXT(counts) failed: {}", result);
                    return;
                }

                logFaultDetails(device, counts, stack);
            } catch (Throwable t) {
                LOGGER.error("Failed to query VK_EXT_device_fault after device loss", t);
            }
        } finally {
            logHistory("recent", history);
            logHistory("query anomalies", anomalies);
        }
    }

    private static void logHistory(String label, List<GpuCrashHistory.Entry> entries) {
        LOGGER.error("Vulkan lifetime {}: {} entries; semaphore kind 1=graphics/2=compute; query flags 1=submitted/2=ended/4=BLAS", label, entries.size());
        for (var entry : entries) {
            LOGGER.error("GPU history #{} ns={} thread={} {} handle=0x{} target={} observed={} detail={} (0x{})",
                    entry.sequence(), entry.nanoTime(), entry.threadId(), entry.event(),
                    Long.toUnsignedString(entry.handle(), 16), entry.target(), entry.observed(),
                    entry.detail(), Long.toUnsignedString(entry.detail(), 16));
        }
    }

    private static void logFaultDetails(VkDevice device, VkDeviceFaultCountsEXT counts, MemoryStack stack) {
        int addressCount = counts.addressInfoCount();
        int vendorCount = counts.vendorInfoCount();
        // Driver-sized reports can exceed the thread-local stack; retain every record in native heap storage.
        try (var addresses = addressCount == 0 ? null : VkDeviceFaultAddressInfoEXT.calloc(addressCount);
             var vendors = vendorCount == 0 ? null : VkDeviceFaultVendorInfoEXT.calloc(vendorCount)) {
            VkDeviceFaultInfoEXT info = VkDeviceFaultInfoEXT.calloc(stack).sType$Default();
            // LWJGL exposes these output pointer fields as getters only; Vulkan requires caller-owned arrays.
            MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PADDRESSINFOS,
                    addresses == null ? 0L : addresses.address());
            MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PVENDORINFOS,
                    vendors == null ? 0L : vendors.address());
            counts.addressInfoCount(addressCount).vendorInfoCount(vendorCount)
                    .vendorBinarySize(0L);

            int result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(device, counts, info);
            if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
                LOGGER.error("vkGetDeviceFaultInfoEXT(info) failed: {}", result);
                return;
            }
            LOGGER.error("Vulkan device fault: description='{}', addresses={}, vendorRecords={}",
                    info.descriptionString(), counts.addressInfoCount(), counts.vendorInfoCount());
            if (addresses != null) {
                for (int i = 0; i < Math.min(addressCount, counts.addressInfoCount()); i++) {
                    VkDeviceFaultAddressInfoEXT address = addresses.get(i);
                    String resource = switch (address.addressType()) {
                        case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_READ_INVALID_EXT,
                             EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_WRITE_INVALID_EXT ->
                                resolveBuffer(address.reportedAddress());
                        default -> "not a buffer access";
                    };
                    LOGGER.error("Vulkan fault address[{}]: type={}, address=0x{}, precision=0x{}, resource={}",
                            i, addressType(address.addressType()), Long.toUnsignedString(address.reportedAddress(), 16),
                            Long.toUnsignedString(address.addressPrecision(), 16), resource);
                }
            }
            if (vendors != null) {
                for (int i = 0; i < Math.min(vendorCount, counts.vendorInfoCount()); i++) {
                    VkDeviceFaultVendorInfoEXT vendor = vendors.get(i);
                    LOGGER.error("Vulkan vendor fault[{}]: description='{}', code=0x{}, data=0x{}",
                            i, vendor.descriptionString(), Long.toUnsignedString(vendor.vendorFaultCode(), 16),
                            Long.toUnsignedString(vendor.vendorFaultData(), 16));
                }
            }
        }
    }

    private static void logRuntimeSnapshot() {
        LOGGER.error("Vulkan in-flight state: {}", IN_FLIGHT);
        long totalBytes = BUFFERS.values().stream().mapToLong(range -> range.bytes().byteSize()).sum();
        LOGGER.error("Caustica live BDA buffers: count={}, bytes={}", BUFFERS.size(), formatBytes(totalBytes));
        long liveAllocator = allocator;
        if (liveAllocator != 0L && memoryHeapCount > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VmaBudget.Buffer budgets = VmaBudget.calloc(memoryHeapCount, stack);
                Vma.vmaGetHeapBudgets(liveAllocator, budgets);
                for (int i = 0; i < memoryHeapCount; i++) {
                    VmaBudget budget = budgets.get(i);
                    LOGGER.error(
                            "VMA heap[{}]: usage={}, budget={}, blocks={}, allocations={}, blockBytes={}, allocationBytes={}",
                            i, formatBytes(budget.usage()), formatBytes(budget.budget()),
                            budget.statistics().blockCount(), budget.statistics().allocationCount(),
                            formatBytes(budget.statistics().blockBytes()), formatBytes(budget.statistics().allocationBytes()));
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to collect VMA budgets after device loss", t);
            }
        }
    }

    private static void logNvQueueCheckpoints(VkQueue queue, String label) {
        if (queue.getCapabilities().vkGetQueueCheckpointDataNV == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer count = stack.callocInt(1);
            NVDeviceDiagnosticCheckpoints.vkGetQueueCheckpointDataNV(queue, count, null);
            int checkpointCount = Math.min(count.get(0), MAX_QUEUE_CHECKPOINTS);
            if (checkpointCount == 0) {
                LOGGER.error("NVIDIA checkpoints for {} queue 0x{}: <none>", label,
                        Long.toUnsignedString(queue.address(), 16));
                return;
            }
            VkCheckpointDataNV.Buffer checkpoints = VkCheckpointDataNV.calloc(checkpointCount, stack);
            for (int i = 0; i < checkpointCount; i++) {
                checkpoints.get(i).sType$Default();
            }
            count.put(0, checkpointCount);
            NVDeviceDiagnosticCheckpoints.vkGetQueueCheckpointDataNV(queue, count, checkpoints);
            LOGGER.error("NVIDIA checkpoints for {} queue 0x{} ({}):", label,
                    Long.toUnsignedString(queue.address(), 16), count.get(0));
            for (int i = 0; i < Math.min(checkpointCount, count.get(0)); i++) {
                VkCheckpointDataNV checkpoint = checkpoints.get(i);
                long marker = checkpoint.pCheckpointMarker();
                var entry = GpuDiagnosticCheckpoints.resolve(marker);
                if (entry == null) {
                    LOGGER.error("  stage={} (0x{}), marker=0x{}, label=<unknown or expired>",
                            pipelineStage(checkpoint.stage()), Integer.toUnsignedString(checkpoint.stage(), 16),
                            Long.toUnsignedString(marker, 16));
                } else {
                    LOGGER.error("  stage={} (0x{}), marker=0x{}, command=0x{}, boundary={}, label='{}'",
                            pipelineStage(checkpoint.stage()), Integer.toUnsignedString(checkpoint.stage(), 16),
                            Long.toUnsignedString(marker, 16), Long.toUnsignedString(entry.command(), 16),
                            entry.boundary(), entry.label());
                    LOGGER.error("  Nearby recorded checkpoints (same command; not execution progress):");
                    for (var nearby : GpuDiagnosticCheckpoints.neighborhood(marker)) {
                        LOGGER.error("    marker=0x{}, boundary={}, label='{}'{}",
                                Long.toUnsignedString(nearby.token(), 16), nearby.boundary(), nearby.label(),
                                nearby.token() == marker ? " [reported]" : "");
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to retrieve NVIDIA checkpoints for " + label, t);
        }
    }

    private static String resolveBuffer(long address) {
        if (address == 0L) {
            return "unresolved (reported null address)";
        }
        VulkanDeviceAddress reportedAddress = new VulkanDeviceAddress(address);
        var entry = BUFFERS.floorEntry(reportedAddress);
        if (entry != null && entry.getValue().contains(reportedAddress)) {
            BufferRange range = entry.getValue();
            long rangeAddress = range.bytes().address().value();
            return "'" + range.label + "' handle=0x" + Long.toUnsignedString(range.handle, 16)
                    + " range=0x" + Long.toUnsignedString(rangeAddress, 16) + "+" + range.bytes().byteSize()
                    + " offset=" + Long.toUnsignedString(address - rangeAddress);
        }
        var next = BUFFERS.ceilingEntry(reportedAddress);
        String nearest = entry == null ? "none" : "prev='" + entry.getValue().label + "'@0x"
                + Long.toUnsignedString(entry.getKey().value(), 16);
        if (next != null) {
            nearest += ", next='" + next.getValue().label + "'@0x"
                    + Long.toUnsignedString(next.getKey().value(), 16);
        }
        return "unresolved (" + nearest + ")";
    }

    public static boolean queryDeviceFaultSupport(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFaultFeaturesEXT fault = VkPhysicalDeviceFaultFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(fault.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice, features);
            return fault.deviceFault();
        }
    }

    public static synchronized void logStartup(VkPhysicalDevice physicalDevice, StartupInfo info) {
        if (startupLogged) {
            return;
        }
        startupLogged = true;
        Runtime runtime = Runtime.getRuntime();
        long physicalMemory = -1L;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            physicalMemory = os.getTotalMemorySize();
        }
        String loaderVersion = "unknown";
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer version = stack.mallocInt(1);
            if (VK11.vkEnumerateInstanceVersion(version) == VK10.VK_SUCCESS) {
                loaderVersion = VulkanRequiredProfile.formatApiVersion(version.get(0));
            }
        }
        LOGGER.info(
                "System: os='{} {}' arch={}, cpu='{}', logicalProcessors={}, physicalMemory={}, java='{} {}' vm='{}', heapMax={}, LWJGL={}, VulkanLoader={}",
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"), runtime.availableProcessors(),
                formatBytes(physicalMemory), System.getProperty("java.vendor"), System.getProperty("java.version"),
                System.getProperty("java.vm.name"), formatBytes(runtime.maxMemory()), Version.getVersion(), loaderVersion);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
            VkPhysicalDeviceProperties properties = properties2.properties();
            LOGGER.info(
                    "Vulkan GPU: name='{}', vendor={} (0x{}), deviceId=0x{}, type={}, api={}, driver='{}' info='{}' driverId={}, driverVersion=0x{}",
                    info.deviceName(), info.vendorName(), Integer.toHexString(properties.vendorID()),
                    Integer.toHexString(properties.deviceID()), info.deviceType(), VulkanRequiredProfile.formatApiVersion(properties.apiVersion()),
                    info.driverName(), info.driverInfo(), info.driverId(),
                    Integer.toHexString(properties.driverVersion()));
            LOGGER.info("Vulkan IDs: deviceUUID={}, driverUUID={}, conformance={}",
                    info.deviceUuid(), info.driverUuid(), info.conformance());
            LOGGER.info(
                    "Vulkan limits: maxAllocationCount={}, nonCoherentAtomSize={}, bufferImageGranularity={}, maxStorageBufferRange={}, maxImage2D={}x{}",
                    Integer.toUnsignedLong(properties.limits().maxMemoryAllocationCount()), formatBytesExact(properties.limits().nonCoherentAtomSize()),
                    formatBytesExact(properties.limits().bufferImageGranularity()),
                    formatBytes(Integer.toUnsignedLong(properties.limits().maxStorageBufferRange())),
                    properties.limits().maxImageDimension2D(), properties.limits().maxImageDimension2D());
        }
        logMemoryAndQueues(physicalDevice);
        LOGGER.info("Vulkan selected queues: {}", info.selectedQueues());
    }

    private static void logMemoryAndQueues(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties2 memory2 = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceMemoryProperties2(physicalDevice, memory2);
            VkPhysicalDeviceMemoryProperties memory = memory2.memoryProperties();
            memoryHeapCount = memory.memoryHeapCount();
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                var heap = memory.memoryHeaps(i);
                LOGGER.info("Vulkan memory heap[{}]: size={}, flags={}", i, formatBytes(heap.size()),
                        memoryHeapFlags(heap.flags()));
            }
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                var type = memory.memoryTypes(i);
                LOGGER.info("Vulkan memory type[{}]: heap={}, flags={}", i, type.heapIndex(),
                        memoryPropertyFlags(type.propertyFlags()));
            }

            java.nio.IntBuffer count = stack.callocInt(1);
            VK11.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, count, null);
            VkQueueFamilyProperties2.Buffer queues = VkQueueFamilyProperties2.calloc(count.get(0), stack);
            for (int i = 0; i < queues.capacity(); i++) queues.get(i).sType$Default();
            VK11.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, count, queues);
            for (int i = 0; i < queues.capacity(); i++) {
                VkQueueFamilyProperties queue = queues.get(i).queueFamilyProperties();
                LOGGER.info("Vulkan queue family[{}]: count={}, flags={}, timestampBits={}",
                        i, queue.queueCount(), queueFlags(queue.queueFlags()), queue.timestampValidBits());
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) return "unknown";
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String formatBytesExact(long bytes) {
        return bytes + " B (" + formatBytes(bytes) + ")";
    }

    private static String memoryHeapFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) names.add("DEVICE_LOCAL");
        if ((flags & VK11.VK_MEMORY_HEAP_MULTI_INSTANCE_BIT) != 0) names.add("MULTI_INSTANCE");
        return names.isEmpty() ? "0" : String.join("|", names);
    }

    private static String memoryPropertyFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) names.add("DEVICE_LOCAL");
        if ((flags & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) names.add("HOST_VISIBLE");
        if ((flags & VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) names.add("HOST_COHERENT");
        if ((flags & VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0) names.add("HOST_CACHED");
        if ((flags & VK10.VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) != 0) names.add("LAZY");
        if ((flags & VK11.VK_MEMORY_PROPERTY_PROTECTED_BIT) != 0) names.add("PROTECTED");
        return names.isEmpty() ? "0" : String.join("|", names);
    }

    private static String queueFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) names.add("GRAPHICS");
        if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) != 0) names.add("COMPUTE");
        if ((flags & VK10.VK_QUEUE_TRANSFER_BIT) != 0) names.add("TRANSFER");
        if ((flags & VK10.VK_QUEUE_SPARSE_BINDING_BIT) != 0) names.add("SPARSE");
        if ((flags & VK11.VK_QUEUE_PROTECTED_BIT) != 0) names.add("PROTECTED");
        return names.isEmpty() ? "0" : String.join("|", names);
    }

    private static String addressType(int type) {
        return switch (type) {
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_NONE_EXT -> "NONE";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_READ_INVALID_EXT -> "READ_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_WRITE_INVALID_EXT -> "WRITE_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_EXECUTE_INVALID_EXT -> "EXECUTE_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_UNKNOWN_EXT -> "IP_UNKNOWN";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_INVALID_EXT -> "IP_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_FAULT_EXT -> "IP_FAULT";
            default -> "UNKNOWN(" + type + ")";
        };
    }

    private static String pipelineStage(int stage) {
        return switch (stage) {
            case VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT -> "TOP_OF_PIPE";
            case VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT -> "DRAW_INDIRECT";
            case VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT -> "VERTEX_INPUT";
            case VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT -> "VERTEX_SHADER";
            case VK10.VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT -> "TESSELLATION_CONTROL";
            case VK10.VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT -> "TESSELLATION_EVALUATION";
            case VK10.VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT -> "GEOMETRY_SHADER";
            case VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT -> "FRAGMENT_SHADER";
            case VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT -> "EARLY_FRAGMENT_TESTS";
            case VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT -> "LATE_FRAGMENT_TESTS";
            case VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT -> "COLOR_ATTACHMENT_OUTPUT";
            case VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT -> "COMPUTE_SHADER";
            case VK10.VK_PIPELINE_STAGE_TRANSFER_BIT -> "TRANSFER";
            case VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT -> "BOTTOM_OF_PIPE";
            case VK10.VK_PIPELINE_STAGE_HOST_BIT -> "HOST";
            case VK10.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT -> "ALL_GRAPHICS";
            case VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT -> "ALL_COMMANDS";
            default -> "UNKNOWN";
        };
    }
}
