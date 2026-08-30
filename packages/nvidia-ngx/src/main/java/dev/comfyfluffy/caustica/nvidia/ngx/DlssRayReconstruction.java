package dev.comfyfluffy.caustica.nvidia.ngx;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * DLSS Ray Reconstruction backend for the RT renderer. Runs the DLSSD (Ray Reconstruction) feature
 * over path-traced color + guide buffers (normals/roughness, diffuse/specular albedo, depth, motion
 * vectors, reflection motion vectors), denoising and upscaling (render res → display res) in one pass.
 */
public final class DlssRayReconstruction {
    private static final Logger LOGGER = LoggerFactory.getLogger(DlssRayReconstruction.class);

    public record Settings(boolean enabled, int quality, int preset) {
    }

    private final NgxRuntime runtime;
    private Settings settings;

    /** Desired RR mode for session resource sizing, including RT startup frames rendered by the source renderer. */
    public boolean configured() {
        return settings.enabled();
    }

    public boolean enabled() {
        return configured() && !failed;
    }

    // DLSS feature flags. IsHDR (bit 0): color is scene-linear ACEScg HDR (rgba16f). MVLowRes (bit 1):
    // motion vectors are at render/input resolution, not display. Depth is positive linear view depth
    // and MVs are unjittered. DLSS-RR does not support the exposure or auto-exposure options.
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES;
    // 0 = let the RR DLL pick its per-mode default preset.
    private int renderPreset() {
        return settings.preset();
    }

    public int quality() {
        return settings.quality();
    }

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailable;

    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private int featurePreset = Integer.MIN_VALUE;

    private boolean resetHistory;
    private long lastFrameNanos;

    public DlssRayReconstruction(NgxRuntime runtime, Settings settings) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void configure(Settings settings) {
        Settings next = Objects.requireNonNull(settings, "settings");
        if (!next.equals(this.settings)) {
            resetHistory();
        }
        this.settings = next;
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /** Discard temporal reconstruction state before the next evaluation. */
    public void resetHistory() {
        resetHistory = true;
        lastFrameNanos = 0L;
    }

    /**
     * Record a DLSS-RR evaluation: denoise + upscale the noisy path-traced color (at render res) using
     * the guide buffers, writing the display-res result into {@code out}. {@code jitterX/jitterY} is the
     * sub-pixel camera jitter applied to the primary ray this frame, in render pixels. Returns false
     * (disabling RR) on failure. MVs are already in render-pixel space (scale 1).
     */
    public boolean evaluate(VkCommandBuffer commandBuffer, GpuImage color, GpuImage depth, GpuImage motion,
                            GpuImage diffuseAlbedo, GpuImage specularAlbedo, GpuImage normals,
                            GpuImage specularMotion, GpuImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.evaluateDlssd(commandBuffer.address(), feature,
                    color.view(), color.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view(), depth.image(), VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view(), motion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                    diffuseAlbedo.view(), diffuseAlbedo.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularAlbedo.view(), specularAlbedo.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    normals.view(), normals.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularMotion.view(), specularMotion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                    0L, 0L, 0,
                    out.view(), out.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    // Jitter and motion vectors use render-pixel units.
                    jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs);
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssd failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-RR evaluate failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Asks NGX what render resolution the current quality mode expects for the given display size.
     * Returns {@code null} only when RR is off (or already disabled from an earlier failure elsewhere)
     * — in that state there is no feature to query and the caller should trace at full resolution.
     * Once RR is active, a failed query (stale shim, old driver, bad NGX result) throws instead of
     * silently falling back, so a broken render/display sync is never masked.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!configured() || failed) {
            return null;
        }
        ensureInitialized();
        if (!lib.hasQueryOptimalDlssd()) {
            throw new IllegalStateException("ngxshim is missing ngxshim_query_optimal_dlssd (stale native shim)");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outWidth = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outHeight = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outSharpness = arena.allocate(ValueLayout.JAVA_FLOAT);
            int rc = lib.queryOptimalDlssd(displayWidth, displayHeight, quality(),
                    outWidth, outHeight, outSharpness);
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_query_optimal_dlssd failed: 0x" + Integer.toHexString(rc));
            }
            int renderWidth = outWidth.get(ValueLayout.JAVA_INT, 0);
            int renderHeight = outHeight.get(ValueLayout.JAVA_INT, 0);
            if (renderWidth <= 0 || renderHeight <= 0) {
                throw new IllegalStateException(
                        "ngxshim_query_optimal_dlssd returned invalid render size " + renderWidth + "x" + renderHeight);
            }
            return new int[] { renderWidth, renderHeight };
        }
    }

    /**
     * Ensure NGX is initialized and an RR feature exists for the given resolutions, creating it into
     * the supplied recording command buffer. Returns false (and disables itself) on any failure so the
     * caller falls back to the non-RR path.
     */
    public boolean ensureFeature(VkCommandBuffer commandBuffer, int renderWidth, int renderHeight,
                                 int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return false;
        }
        try {
            ensureInitialized();
            int quality = quality();
            int preset = renderPreset();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || featurePreset != preset
                    || isNull(feature)) {
                releaseFeature();
                feature = lib.createDlssd(commandBuffer.address(), renderWidth, renderHeight,
                        displayWidth, displayHeight, quality, FEATURE_FLAGS, preset);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlssd failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                featurePreset = preset;
                resetHistory = true; // a fresh feature has no temporal history
                LOGGER.info("DLSS-RR feature created: {}x{} -> {}x{} (quality {}, preset {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-RR setup failed; RT composite continues without it", t);
            return false;
        }
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        // NGX init/shutdown is owned by the shared NgxRuntime so RR and Frame Generation can coexist
        // (releasing the RR feature must not tear NGX down while FG still holds a handle).
        lib = runtime.acquire();
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS-RR cannot initialize");
        }
        boolean available = lib.dlssdAvailable();
        if (!loggedAvailable) {
            loggedAvailable = true;
            LOGGER.info("DLSS Ray Reconstruction available: {}", available);
        }
        if (!available) {
            throw new IllegalStateException("DLSS Ray Reconstruction is not available on this system");
        }
        initialized = true;
    }

    /**
     * Release the RR feature after all submitted work that can reference it has completed.
     */
    public void destroyAfterDeviceIdle() {
        releaseFeature();
        initialized = false;
        failed = false;
        lib = null;
        resetHistory = true;
        lastFrameNanos = 0L;
    }

    private void releaseFeature() {
        if (!isNull(feature)) {
            lib.release(feature);
            feature = MemorySegment.NULL;
        }
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureDisplayWidth = -1;
        featureDisplayHeight = -1;
        featureQuality = Integer.MIN_VALUE;
        featurePreset = Integer.MIN_VALUE;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }

}
