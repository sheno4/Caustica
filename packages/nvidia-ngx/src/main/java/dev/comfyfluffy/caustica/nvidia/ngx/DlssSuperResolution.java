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

/** DLSS Super Resolution feature for a separately denoised, scene-linear input image. */
public final class DlssSuperResolution {
    private static final Logger LOGGER = LoggerFactory.getLogger(DlssSuperResolution.class);
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES
            | FEATURE_FLAG_DEPTH_INVERTED;

    public record Settings(boolean enabled, int quality, int preset) {
        public Settings {
            if (quality < 0 || quality > 5 || quality == 4) {
                throw new IllegalArgumentException("unsupported DLSS-SR quality " + quality);
            }
            if (preset != 0 && (preset < 10 || preset > 13)) {
                throw new IllegalArgumentException("unsupported DLSS-SR render preset " + preset);
            }
        }
    }

    private final NgxRuntime runtime;
    private Settings settings;
    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailable;
    private boolean resetHistory = true;
    private long lastFrameNanos;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private int featurePreset = Integer.MIN_VALUE;

    public DlssSuperResolution(NgxRuntime runtime, Settings settings) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void configure(Settings settings) {
        Settings next = Objects.requireNonNull(settings, "settings");
        if (!next.equals(this.settings)) resetHistory();
        this.settings = next;
    }

    public boolean configured() {
        return settings.enabled();
    }

    public int configurationKey() {
        return Objects.hash(settings.quality(), settings.preset());
    }

    public void resetHistory() {
        resetHistory = true;
        lastFrameNanos = 0L;
    }

    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!configured() || failed) return null;
        try {
            ensureInitialized();
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("DLSS-SR is unavailable; NRD continues at display resolution", t);
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outWidth = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outHeight = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outSharpness = arena.allocate(ValueLayout.JAVA_FLOAT);
            int rc = lib.queryOptimal(displayWidth, displayHeight, settings.quality(),
                    outWidth, outHeight, outSharpness);
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_query_optimal failed: 0x" + Integer.toHexString(rc));
            }
            int renderWidth = outWidth.get(ValueLayout.JAVA_INT, 0);
            int renderHeight = outHeight.get(ValueLayout.JAVA_INT, 0);
            if (renderWidth <= 0 || renderHeight <= 0) {
                throw new IllegalStateException("ngxshim_query_optimal returned invalid render size "
                        + renderWidth + "x" + renderHeight);
            }
            return new int[]{renderWidth, renderHeight};
        }
    }

    public boolean ensureFeature(VkCommandBuffer commandBuffer, int renderWidth, int renderHeight,
                                 int displayWidth, int displayHeight) {
        if (!configured() || failed) return false;
        try {
            ensureInitialized();
            int quality = settings.quality();
            int preset = settings.preset();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || featurePreset != preset || isNull(feature)) {
                releaseFeature();
                feature = lib.createDlss(commandBuffer.address(), renderWidth, renderHeight,
                        displayWidth, displayHeight, quality, FEATURE_FLAGS, preset);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlss failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                featurePreset = preset;
                resetHistory();
                LOGGER.info("DLSS-SR feature created: {}x{} -> {}x{} (quality {}, preset {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-SR setup failed; RT composite continues with linear upscale", t);
            return false;
        }
    }

    public boolean evaluate(VkCommandBuffer commandBuffer, GpuImage color, GpuImage depth, GpuImage motion,
                            GpuImage output, int renderWidth, int renderHeight,
                            int displayWidth, int displayHeight, float jitterX, float jitterY,
                            boolean reset, float preExposure) {
        if (!initialized || failed || isNull(feature)) return false;
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0L ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;
            int rc = lib.evaluate(commandBuffer.address(), feature,
                    color.view(), color.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view(), depth.image(), VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view(), motion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                    output.view(), output.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    jitterX, jitterY, 1.0f, 1.0f,
                    reset || resetHistory ? 1 : 0, frameMs, preExposure);
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-SR evaluate failed; RT composite continues with linear upscale", t);
            return false;
        }
    }

    private void ensureInitialized() {
        if (initialized) return;
        lib = runtime.acquire();
        if (lib == null) throw new IllegalStateException("NGX runtime unavailable; DLSS-SR cannot initialize");
        boolean available = lib.dlssAvailable();
        if (!loggedAvailable) {
            loggedAvailable = true;
            LOGGER.info("DLSS Super Resolution available: {}", available);
        }
        if (!available) throw new IllegalStateException("DLSS Super Resolution is not available on this system");
        initialized = true;
    }

    public void destroyAfterDeviceIdle() {
        releaseFeature();
        initialized = false;
        failed = false;
        lib = null;
        resetHistory();
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
