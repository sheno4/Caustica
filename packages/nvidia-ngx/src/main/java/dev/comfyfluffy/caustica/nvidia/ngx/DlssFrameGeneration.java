package dev.comfyfluffy.caustica.nvidia.ngx;

import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * DLSS Frame Generation (DLSSG) backend. Shares the NGX instance with DLSS-RR via {@link NgxRuntime};
 * owns the DLSSG feature handle, probes availability, and records one interpolated frame per rendered
 * frame. The owner supplies the activation setting; hardware and driver availability are probed here.
 */
public final class DlssFrameGeneration {
    private static final Logger LOGGER = LoggerFactory.getLogger(DlssFrameGeneration.class);

    public record Settings(boolean enabled) {
    }

    private final NgxRuntime runtime;
    private Settings settings;

    public boolean enabled() {
        return settings.enabled() && !failed;
    }

    private NgxLibrary lib;
    private Feature feature;
    private boolean failed;
    private boolean probed;
    private boolean available;

    private record Feature(MemorySegment handle, int width, int height,
                           int renderWidth, int renderHeight, int backbufferFormat) { }

    public DlssFrameGeneration(NgxRuntime runtime, Settings settings) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isReady() {
        return !failed && feature != null;
    }

    /** Whether a live feature already matches these dimensions/format (no recreate needed). */
    public boolean featureReadyFor(int width, int height, int renderWidth, int renderHeight, int backbufferFormat) {
        return isReady() && feature.width() == width && feature.height() == height
                && feature.renderWidth() == renderWidth && feature.renderHeight() == renderHeight
                && feature.backbufferFormat() == backbufferFormat;
    }

    /**
     * Probe DLSSG availability once after NGX is up. Safe to call every tick
     * when FG is enabled; no-op after the first successful probe. Needs no command buffer (capability query).
     */
    public void probeAvailabilityOnce() {
        if (probed || failed) {
            return;
        }
        lib = runtime.acquire();
        if (lib == null) {
            return;
        }
        probed = true;
        available = lib.dlssgAvailable();
        LOGGER.info("DLSS Frame Generation available: {}", available);
    }

    /**
     * Ensure a DLSSG feature exists for the given backbuffer/render size + native backbuffer format, creating
     * it into the supplied recording command buffer. The caller must complete prior uses before changing
     * the feature. Returns false (and disables itself) on failure.
     */
    public boolean ensureFeature(VkCommandBuffer commandBuffer, int width, int height,
                                 int renderWidth, int renderHeight, int backbufferFormat) {
        if (!enabled()) {
            return false;
        }
        try {
            probeAvailabilityOnce();
            if (!available) {
                throw new IllegalStateException("DLSS Frame Generation is not available on this system");
            }
            if (!featureReadyFor(width, height, renderWidth, renderHeight, backbufferFormat)) {
                releaseFeature();
                MemorySegment handle = lib.createDlssg(commandBuffer.address(), width, height, backbufferFormat);
                if (handle.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("ngxshim_create_dlssg failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                feature = new Feature(handle, width, height, renderWidth, renderHeight, backbufferFormat);
                LOGGER.info("DLSS-FG feature created: {}x{} (render {}x{}, backbuffer format {})",
                        width, height, renderWidth, renderHeight, backbufferFormat);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-FG setup failed; frame generation disabled", t);
            return false;
        }
    }

    /**
     * Record one DLSSG evaluation halfway between rendered frames from the final {@code backbuffer},
     * hardware {@code depth}, and {@code mvec} into
     * {@code outputInterp}. {@code hudless} (the main scene before the combined UI overlay) and {@code ui}
     * (premultiplied combined overlay: RT world overlays, hand/screen effects and GUI) help the driver avoid
     * ghosting/smearing screen-fixed content in the generated frame; both are optional — pass 0 handles
     * (view/image/format) to skip. The host presents the real frame itself. Matrices are jitter-free (NGX left-multiply
     * layout); pass {@code null} to leave one out. Returns false on failure.
     */
    public boolean evaluate(VkCommandBuffer commandBuffer,
            long backbufferView, long backbufferImage, int backbufferFormat,
            long depthView, long depthImage, int depthFormat,
            long mvecView, long mvecImage, int mvecFormat,
            long hudlessView, long hudlessImage, int hudlessFormat,
            long uiView, long uiImage, int uiFormat,
            long outputInterpView, long outputInterpImage, int outputInterpFormat,
            int width, int height, int mvecDepthWidth, int mvecDepthHeight,
            float mvScaleX, float mvScaleY,
            boolean depthInverted, boolean colorBuffersHDR, boolean cameraMotionIncluded, boolean reset,
            Matrix4fc clipToPrevClip, Matrix4fc prevClipToClip) {
        if (!isReady()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment clipToPrev = matrixSegment(arena, clipToPrevClip);
            MemorySegment prevToClip = matrixSegment(arena, prevClipToClip);
            int rc = lib.evaluateDlssg(commandBuffer.address(), feature.handle(),
                    backbufferView, backbufferImage, backbufferFormat,
                    depthView, depthImage, depthFormat,
                    mvecView, mvecImage, mvecFormat,
                    hudlessView, hudlessImage, hudlessFormat, // 0/0/0 = skip (no hudless capture this frame)
                    uiView, uiImage, uiFormat, // 0/0/0 = skip (UI overlay not active this frame)
                    outputInterpView, outputInterpImage, outputInterpFormat,
                    0L, 0L, 0, // outputReal (skip; MC presents the real frame itself)
                    width, height, mvecDepthWidth, mvecDepthHeight,
                    mvScaleX, mvScaleY,
                    depthInverted ? 1 : 0, colorBuffersHDR ? 1 : 0, cameraMotionIncluded ? 1 : 0, reset ? 1 : 0,
                    MemorySegment.NULL, MemorySegment.NULL, clipToPrev, prevToClip);
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssg_2x failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-FG evaluate failed; frame generation disabled", t);
            return false;
        }
    }

    /** Format a JOML matrix into NGX left-multiply (row-major) layout, or NULL for a null matrix. */
    private static MemorySegment matrixSegment(Arena arena, Matrix4fc m) {
        if (m == null) {
            return MemorySegment.NULL;
        }
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
        m.get(seg.asByteBuffer().order(ByteOrder.nativeOrder()));
        return seg;
    }

    /** Release the FG feature after all submitted work that can reference it has completed. */
    public void destroyAfterDeviceIdle() {
        releaseFeature();
        failed = false;
        probed = false;
        available = false;
        lib = null;
    }

    private void releaseFeature() {
        if (feature != null) {
            lib.release(feature.handle());
        }
        feature = null;
    }

}
