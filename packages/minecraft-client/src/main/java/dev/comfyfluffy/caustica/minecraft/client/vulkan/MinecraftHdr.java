package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTHdrMetadata;
import org.lwjgl.vulkan.KHRGetSurfaceCapabilities2;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkHdrMetadataEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceSurfaceInfo2KHR;
import org.lwjgl.vulkan.VkSurfaceFormat2KHR;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;

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
    // VK_EXT_swapchain_colorspace color-space enum values (not all are in the LWJGL VK10 constants).
    private static final int CS_SRGB_NONLINEAR = 0;
    private static final int CS_DISPLAY_P3_NONLINEAR = 1000104001;
    private static final int CS_EXTENDED_SRGB_LINEAR = 1000104002;
    private static final int CS_DISPLAY_P3_LINEAR = 1000104003;
    private static final int CS_DCI_P3_NONLINEAR = 1000104004;
    private static final int CS_BT709_LINEAR = 1000104005;
    private static final int CS_BT709_NONLINEAR = 1000104006;
    private static final int CS_BT2020_LINEAR = 1000104007;
    private static final int CS_HDR10_ST2084 = 1000104008;
    private static final int CS_DOLBYVISION = 1000104009;
    private static final int CS_HDR10_HLG = 1000104010;
    private static final int CS_ADOBERGB_LINEAR = 1000104011;
    private static final int CS_ADOBERGB_NONLINEAR = 1000104012;
    private static final int CS_PASS_THROUGH = 1000104013;
    private static final int CS_EXTENDED_SRGB_NONLINEAR = 1000104014;

    private static volatile boolean surfaceLogged;

    private MinecraftHdr() {
    }

    /**
     * Assigns SMPTE ST 2086 / CTA-861.3 static metadata to one PQ swapchain.
     *
     * <p>The ACES HDR output LUT is a Rec.2020/D65 virtual master capped at one of the baked mastering
     * peaks, so that peak is both the mastering-display maximum and MaxCLL. MaxFALL cannot be known without
     * analysing every rendered frame; Vulkan explicitly permits unknown fields to be zero, which is more
     * truthful than inventing a scene-average value.
     */
    public static boolean applyMasteringMetadata(VkDevice device, long swapchain, int masteringPeakNits) {
        if (swapchain == 0L) {
            return false;
        }
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
        return true;
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

    /** Logs the resolved HDR config once (cheap; safe to call repeatedly — guarded by the surface log). */
    public static void logConfig() {
        CausticaMod.LOGGER.info(
                "HDR config: enabled={} ui={}nits peak={}nits -> {}",
                dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition.current().runtime().hdrEnabled(),
                CausticaConfig.get(RendererOptions.Rt.Hdr.UI_NITS), CausticaConfig.get(RendererOptions.Rt.Hdr.PEAK_NITS),
                dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition.current().runtime().hdrEnabled() ? "HDR display path active" : "SDR display path");
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

            boolean anyHdr = false;
            CausticaMod.LOGGER.info("HDR: chosen swapchain image format={} ({}); surface advertises {} (format,colorSpace) pair(s):",
                    chosenFormat, formatName(chosenFormat), formats.size());
            for (int i = 0; i < formats.size(); i++) {
                SurfaceFormat f = formats.get(i);
                int cs = f.colorSpace();
                boolean hdr = isHdrColorSpace(cs);
                anyHdr |= hdr;
                CausticaMod.LOGGER.info("  [{}] format={} ({}), colorSpace={} ({}){}",
                        i, f.format(), formatName(f.format()), cs, colorSpaceName(cs), hdr ? "  <-- HDR-capable" : "");
            }
            if (anyHdr) {
                CausticaMod.LOGGER.info("HDR: at least one HDR-capable color space is exposed; PQ/HDR10 presentation is available.");
            } else {
                CausticaMod.LOGGER.warn("HDR: only SDR color spaces are exposed by this Vulkan surface. Enable OS/display HDR; on Linux, use native Wayland with HDR enabled in the compositor.");
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
                if (result != org.lwjgl.vulkan.VK10.VK_SUCCESS
                        && result != org.lwjgl.vulkan.VK10.VK_INCOMPLETE) {
                    throw new IllegalStateException("vkGetPhysicalDeviceSurfaceFormats2KHR(count) failed: " + result);
                }
                if (count.get(0) == 0) return List.of();

                VkSurfaceFormat2KHR.Buffer formats = VkSurfaceFormat2KHR.calloc(count.get(0), stack);
                for (int index = 0; index < formats.capacity(); index++) {
                    formats.get(index).sType$Default();
                }
                result = query.query(count, formats);
                if (result == org.lwjgl.vulkan.VK10.VK_INCOMPLETE) continue;
                if (result != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
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

    private static boolean isHdrColorSpace(int cs) {
        return cs == CS_EXTENDED_SRGB_LINEAR || cs == CS_EXTENDED_SRGB_NONLINEAR
                || cs == CS_HDR10_ST2084 || cs == CS_HDR10_HLG || cs == CS_DOLBYVISION
                || cs == CS_BT2020_LINEAR || cs == CS_DISPLAY_P3_LINEAR;
    }

    private static String colorSpaceName(int cs) {
        return switch (cs) {
            case CS_SRGB_NONLINEAR -> "SRGB_NONLINEAR";
            case CS_DISPLAY_P3_NONLINEAR -> "DISPLAY_P3_NONLINEAR";
            case CS_EXTENDED_SRGB_LINEAR -> "EXTENDED_SRGB_LINEAR (scRGB)";
            case CS_DISPLAY_P3_LINEAR -> "DISPLAY_P3_LINEAR";
            case CS_DCI_P3_NONLINEAR -> "DCI_P3_NONLINEAR";
            case CS_BT709_LINEAR -> "BT709_LINEAR";
            case CS_BT709_NONLINEAR -> "BT709_NONLINEAR";
            case CS_BT2020_LINEAR -> "BT2020_LINEAR";
            case CS_HDR10_ST2084 -> "HDR10_ST2084 (PQ)";
            case CS_DOLBYVISION -> "DOLBYVISION";
            case CS_HDR10_HLG -> "HDR10_HLG";
            case CS_ADOBERGB_LINEAR -> "ADOBERGB_LINEAR";
            case CS_ADOBERGB_NONLINEAR -> "ADOBERGB_NONLINEAR";
            case CS_PASS_THROUGH -> "PASS_THROUGH";
            case CS_EXTENDED_SRGB_NONLINEAR -> "EXTENDED_SRGB_NONLINEAR";
            default -> "unknown";
        };
    }

    /** Names the few VkFormat values relevant to swapchain/HDR output; other formats print as the raw enum. */
    private static String formatName(int format) {
        return switch (format) {
            case 37 -> "R8G8B8A8_UNORM";
            case 43 -> "R8G8B8A8_SRGB";
            case 44 -> "B8G8R8A8_UNORM";
            case 50 -> "B8G8R8A8_SRGB";
            case 64 -> "A2R10G10B10_UNORM_PACK32";
            case 97 -> "R16G16B16A16_SFLOAT";
            default -> "VkFormat#" + format;
        };
    }
}
