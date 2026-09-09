package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTHdrMetadata;
import org.lwjgl.vulkan.KHRGetSurfaceCapabilities2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkHdrMetadataEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceSurfaceInfo2KHR;
import org.lwjgl.vulkan.VkSurfaceFormat2KHR;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.EXTSwapchainColorspace.*;
import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;

/**
 * HDR display support — capability detection/logging plus static mastering metadata for PQ swapchains.
 * Surface enumeration tells the swapchain-ownership code whether HDR10 is available on the current driver,
 * window system, compositor, and monitor; {@code VK_EXT_hdr_metadata}, when supported, describes the
 * Rec.2020/D65 ACES virtual mastering display to that presentation stack.
 *
 * <p>Extended color spaces are reported only when the instance enables
 * {@code VK_EXT_swapchain_colorspace}. {@code VulkanInstanceMixin} also requires
 * {@code VK_KHR_get_surface_capabilities2}, which this class uses to report the surface formats and select
 * HDR10/PQ capability from the advertised pairs.
 */
public final class MinecraftHdr {
    private static volatile boolean surfaceLogged;

    private MinecraftHdr() {
    }

    /**
     * Assigns SMPTE ST 2086 / CTA-861.3 static metadata to a live PQ swapchain on a device with
     * {@code VK_EXT_hdr_metadata} enabled.
     *
     * <p>The ACES HDR output LUT is a Rec.2020/D65 virtual master capped at one of the baked mastering
     * peaks, so that peak is both the mastering-display maximum and MaxCLL. MaxFALL cannot be known without
     * analysing every rendered frame; Vulkan explicitly permits unknown fields to be zero, which is more
     * truthful than inventing a scene-average value.
     */
    public static void applyMasteringMetadata(VkDevice device, long swapchain, int masteringPeakNits) {
        MasteringMetadata values = masteringMetadata(masteringPeakNits);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkHdrMetadataEXT.Buffer metadata = VkHdrMetadataEXT.calloc(1, stack);
            VkHdrMetadataEXT entry = metadata.get(0).sType$Default();
            entry.displayPrimaryRed().set(values.red().x(), values.red().y());
            entry.displayPrimaryGreen().set(values.green().x(), values.green().y());
            entry.displayPrimaryBlue().set(values.blue().x(), values.blue().y());
            entry.whitePoint().set(values.white().x(), values.white().y());
            entry.maxLuminance(values.maxLuminance());
            entry.minLuminance(values.minLuminance());
            entry.maxContentLightLevel(values.maxContentLightLevel());
            entry.maxFrameAverageLightLevel(values.maxFrameAverageLightLevel());
            EXTHdrMetadata.vkSetHdrMetadataEXT(device, stack.longs(swapchain), metadata);
        }
        CausticaMod.LOGGER.info(
                "HDR: set swapchain mastering metadata: Rec.2020/D65, min={} nits, peak/MaxCLL={} nits, MaxFALL=unknown",
                values.minLuminance(), values.maxLuminance());
    }

    static MasteringMetadata masteringMetadata(int masteringPeakNits) {
        if (masteringPeakNits <= 0) {
            throw new IllegalArgumentException("masteringPeakNits must be positive");
        }
        float peak = masteringPeakNits;
        return new MasteringMetadata(
                new Chromaticity(0.708f, 0.292f),
                new Chromaticity(0.170f, 0.797f),
                new Chromaticity(0.131f, 0.046f),
                new Chromaticity(0.3127f, 0.3290f),
                peak, 0.0001f, peak, 0.0f);
    }

    record Chromaticity(float x, float y) {
    }

    record MasteringMetadata(
            Chromaticity red,
            Chromaticity green,
            Chromaticity blue,
            Chromaticity white,
            float maxLuminance,
            float minLuminance,
            float maxContentLightLevel,
            float maxFrameAverageLightLevel) {
    }

    public record SurfaceFormat(int format, int colorSpace) {
    }

    @FunctionalInterface
    interface SurfaceFormatQuery {
        int query(IntBuffer count, VkSurfaceFormat2KHR.Buffer formats);
    }

    /** Logs the resolved HDR configuration for the surface capability report. */
    private static void logConfig() {
        boolean enabled = CausticaClientComposition.current().runtime().hdrEnabled();
        CausticaMod.LOGGER.info(
                "HDR config: enabled={} ui={}nits peak={}nits -> {}",
                enabled,
                CausticaConfig.get(RendererOptions.Rt.Hdr.UI_NITS), CausticaConfig.get(RendererOptions.Rt.Hdr.PEAK_NITS),
                enabled ? "HDR display path active" : "SDR display path");
    }

    /**
     * Enumerates and logs the surface's supported (format, color space) pairs. Called once from the
     * {@code VulkanGpuSurface} constructor hook, where both the physical device and the live surface handle
     * are available. No-op on repeat calls.
     */
    public static void logSurfaceCapabilities(VkPhysicalDevice phys, long surface, int chosenFormat) {
        if (surfaceLogged) {
            return;
        }
        surfaceLogged = true;
        logConfig();
        try {
            List<SurfaceFormat> formats = surfaceFormats(phys, surface);
            if (formats.isEmpty()) {
                CausticaMod.LOGGER.warn("HDR: surface advertises no formats");
                return;
            }

            boolean supportsPq = false;
            CausticaMod.LOGGER.info("HDR: chosen swapchain image format={} ({}); surface advertises {} (format,colorSpace) pair(s):",
                    chosenFormat, formatName(chosenFormat), formats.size());
            for (int i = 0; i < formats.size(); i++) {
                SurfaceFormat f = formats.get(i);
                int cs = f.colorSpace();
                boolean pq = cs == VK_COLOR_SPACE_HDR10_ST2084_EXT;
                supportsPq |= pq;
                CausticaMod.LOGGER.info("  [{}] format={} ({}), colorSpace={} ({}){}",
                        i, f.format(), formatName(f.format()), cs, colorSpaceName(cs), pq ? "  <-- HDR10/PQ" : "");
            }
            if (supportsPq) {
                CausticaMod.LOGGER.info("HDR: the surface advertises HDR10/PQ presentation.");
            } else {
                CausticaMod.LOGGER.warn("HDR: the surface does not advertise HDR10/PQ. Check OS/display HDR and compositor support.");
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("HDR: surface capability enumeration failed: {}", t.toString());
        }
    }

    public static List<SurfaceFormat> surfaceFormats(VkPhysicalDevice physicalDevice, long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceSurfaceInfo2KHR surfaceInfo = VkPhysicalDeviceSurfaceInfo2KHR.calloc(stack)
                    .sType$Default().surface(surface);
            return surfaceFormats((count, formats) ->
                    KHRGetSurfaceCapabilities2.vkGetPhysicalDeviceSurfaceFormats2KHR(
                            physicalDevice, surfaceInfo, count, formats));
        }
    }

    static List<SurfaceFormat> surfaceFormats(SurfaceFormatQuery query) {
        while (true) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.callocInt(1);
                int result = query.query(count, null);
                if (result != VK10.VK_SUCCESS
                        && result != VK10.VK_INCOMPLETE) {
                    throw new IllegalStateException("vkGetPhysicalDeviceSurfaceFormats2KHR(count) failed: " + result);
                }
                if (count.get(0) == 0) return List.of();

                VkSurfaceFormat2KHR.Buffer formats = VkSurfaceFormat2KHR.calloc(count.get(0), stack);
                for (int index = 0; index < formats.capacity(); index++) {
                    formats.get(index).sType$Default();
                }
                result = query.query(count, formats);
                if (result == VK10.VK_INCOMPLETE) continue;
                if (result != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkGetPhysicalDeviceSurfaceFormats2KHR(data) failed: " + result);
                }

                int formatCount = Math.min(count.get(0), formats.capacity());
                List<SurfaceFormat> resultFormats = new ArrayList<>(formatCount);
                for (int index = 0; index < formatCount; index++) {
                    var format = formats.get(index).surfaceFormat();
                    resultFormats.add(new SurfaceFormat(format.format(), format.colorSpace()));
                }
                return List.copyOf(resultFormats);
            }
        }
    }

    private static String colorSpaceName(int cs) {
        return switch (cs) {
            case VK_COLOR_SPACE_SRGB_NONLINEAR_KHR -> "SRGB_NONLINEAR";
            case VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT -> "DISPLAY_P3_NONLINEAR";
            case VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT -> "EXTENDED_SRGB_LINEAR (scRGB)";
            case VK_COLOR_SPACE_DISPLAY_P3_LINEAR_EXT -> "DISPLAY_P3_LINEAR";
            case VK_COLOR_SPACE_DCI_P3_NONLINEAR_EXT -> "DCI_P3_NONLINEAR";
            case VK_COLOR_SPACE_BT709_LINEAR_EXT -> "BT709_LINEAR";
            case VK_COLOR_SPACE_BT709_NONLINEAR_EXT -> "BT709_NONLINEAR";
            case VK_COLOR_SPACE_BT2020_LINEAR_EXT -> "BT2020_LINEAR";
            case VK_COLOR_SPACE_HDR10_ST2084_EXT -> "HDR10_ST2084 (PQ)";
            case VK_COLOR_SPACE_DOLBYVISION_EXT -> "DOLBYVISION";
            case VK_COLOR_SPACE_HDR10_HLG_EXT -> "HDR10_HLG";
            case VK_COLOR_SPACE_ADOBERGB_LINEAR_EXT -> "ADOBERGB_LINEAR";
            case VK_COLOR_SPACE_ADOBERGB_NONLINEAR_EXT -> "ADOBERGB_NONLINEAR";
            case VK_COLOR_SPACE_PASS_THROUGH_EXT -> "PASS_THROUGH";
            case VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT -> "EXTENDED_SRGB_NONLINEAR";
            default -> "unknown";
        };
    }

    /** Names the few VkFormat values relevant to swapchain/HDR output; other formats print as the raw enum. */
    private static String formatName(int format) {
        return switch (format) {
            case VK10.VK_FORMAT_R8G8B8A8_UNORM -> "R8G8B8A8_UNORM";
            case VK10.VK_FORMAT_R8G8B8A8_SRGB -> "R8G8B8A8_SRGB";
            case VK10.VK_FORMAT_B8G8R8A8_UNORM -> "B8G8R8A8_UNORM";
            case VK10.VK_FORMAT_B8G8R8A8_SRGB -> "B8G8R8A8_SRGB";
            case VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32 -> "A2R10G10B10_UNORM_PACK32";
            case VK10.VK_FORMAT_R16G16B16A16_SFLOAT -> "R16G16B16A16_SFLOAT";
            default -> "VkFormat#" + format;
        };
    }
}
