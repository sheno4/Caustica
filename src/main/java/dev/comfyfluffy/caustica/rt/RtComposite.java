package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.engine.frame.DamageOverlay;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.light.RtProviderLights;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryMaterialResolver;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtMaterialOverrides;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtDebugPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtJitter;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.shader.Composition;
import dev.comfyfluffy.caustica.rt.shader.WorldShaderCompiler;
import dev.comfyfluffy.caustica.rt.pass.RenderPassManager;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.backend.GraphicsSubmission;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtShaderCode;
import dev.comfyfluffy.caustica.rt.pipeline.RtToneLut;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    public static boolean enabled() {
        return RtRuntime.frameActive();
    }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    private static final AtomicInteger SHADER_THREAD_ID = new AtomicInteger();
    private static final ExecutorService SHADER_BUILD_EXECUTOR = Executors.newSingleThreadExecutor(
            runnable -> shaderThread(runnable, "build"));
    private static final Map<WorldShaderCacheKey, WorldShaderBuild> WORLD_SHADER_CACHE =
            new ConcurrentHashMap<>();
    private static Path shaderCacheRoot;

    public static void configureShaderCacheRoot(Path root) {
        shaderCacheRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    private static Thread shaderThread(Runnable runnable, String role) {
        Thread thread = new Thread(runnable,
                "Caustica shader " + role + '-' + SHADER_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex avoid unnecessary global-memory dereferences; WorldPushConstantsData is
    // generated from the same Slang module and owns this second ABI as well. debugView is no longer
    // part of it -- no world shader reads it anymore; debug views are a downstream compute pass.
    private static final long PATH_RECORD_BYTES = 48L;
    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return CausticaConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static boolean waterWaves() {
        return CausticaConfig.Rt.Composite.WATER_WAVES.value();
    }

    // Modulus of the world-pinned procedural domain anchor. Documented engine constant, not a per-surface
    // tunable: a very low-frequency field could alias across it where the wave spectrum does not.
    private static final int PROCEDURAL_ANCHOR_MASK = 4095;
    // The versioned look package owns every photometric anchor and the sky geometry. Its sun illuminance is the
    // photometric solar constant at the top of the atmosphere; the shader's transmittance LUT brings that
    // to ~117,000 lux under a zenith sun and reddens/dims it through sunset, and because world.rmiss tints
    // the visible disc from the same LUT, the light on terrain and the sky's sunset are one number.
    //
    // world.rgen consumes it as ILLUMINANCE at normal incidence (lux) — the NEE term is brdf·E·ndl with no
    // solid-angle factor, and the diffuse BRDF's 1/π turns 100,000 lux into
    // 31,800 cd/m² white / 5,730 cd/m² 18%-grey noon surface. It is therefore independent of the sky
    // package's angular radii, which only jitter the shadow ray and so only set penumbra softness.
    private static final RtLookPackage LOOK = RtLookPackage.current();
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    // Monotonic per-composite frame counter used for cache eviction, shader sampling, and diagnostics.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    // CPU compilation can outlive an RT session; only completed shader sets cross onto the render thread.
    private PendingWorldShaderBuild pendingWorldShaderBuild;
    private WorldShaderBuild worldShaderBuild;

    private RtPipeline worldPipeline;
    // Set at the start of a host resource reload: a reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundBlockAlbedoAtlasHandle to a fresh non-zero value (deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundBlockAlbedoAtlasHandle;
    private long baseColorAtlasView;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // The RenderPassManager world-resource generation currently written into the world pipeline's set 2.
    private int boundWorldResourceGeneration = -1;
    // Set when a new material epoch is published. The first composite returns to source rasterization so the next
    // client tick can apply RtTerrain's full-clear before any old-epoch primitive IDs are traced.
    private boolean materialEpochTraceGate;
    // World push data lives in a host-visible BDA ring; only the slot address and a small hot subset are
    // pushed inline (the full generated structure exceeds NVIDIA's 256-byte push-constant ceiling).
    // Exact graphics completion guards host writes; ring depth only avoids routine waits.
    private static final int PUSH_RING = 6;
    private PushSlot[] pushRing;
    private int pushSlot;
    private final RtProviderLights providerLights = new RtProviderLights();
    private final RtSceneGeometryManager sceneGeometry = new RtSceneGeometryManager(handle -> {
        int bindingId = RtMaterialRegistry.INSTANCE.bindingId(handle.id());
        return new RtGeometryMaterialResolver.ResolvedMaterial(bindingId,
                RtMaterialRegistry.INSTANCE.sbtClassFor(bindingId));
    });
    private RtDisplayPipeline displayPipeline;
    private RenderPassManager renderPassManager;
    private long renderPassSceneId = Long.MIN_VALUE;
    private RtDebugPresentPipeline debugPresentPipeline;
    private RtToneLut sdrToneLut;
    private RtToneLut hdrToneLut;
    private RtToneLut lookLut;
    private int loadedHdrLutNits = -1;
    private GpuImage output;
    // Packed primary -> indirect continuations. Pass A is fixed at one sample and owns two records per
    // render pixel (base + optional transmission); Pass B resamples them at the configured SPP.
    private GpuBuffer continuationQueue;
    private GpuImage displayImage;
    // Bloom pyramid, finest first: level 0 is half display resolution and each level halves again. The
    // display mapper reads level 0, which the upsample sweep leaves holding the sum of every band.
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private GpuImage hdrDisplayImage;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the host SDR present.
    private boolean hdrWrittenThisFrame;
    // DLSS-FG "hudless" resource: a copy of the main render target before the combined UI overlay
    // composites back on top. Lazily allocated (only meaningful once FG + the UI overlay redirect are both
    // active), resized on demand.
    private GpuImage fgHudlessImage;
    // Same idea as fgHudlessImage but for the HDR present path: a copy of hdrDisplayImage taken in
    // presentHdr right before its own combined-UI composite dispatch overwrites it in place (see
    // captureFgHdrHudless). Already PQ-encoded (same as hdrDisplayImage), so this is a plain image copy, not
    // a format conversion — DLSS-FG requires a display-ready EOTF-encoded [0,1] signal (its programming
    // guide explicitly disallows scRGB), and PQ is exactly that.
    private GpuImage fgHdrHudlessImage;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;

    private static final class PushSlot {
        final GpuBuffer buffer;
        final RtGpuExecutor.TrackedGraphicsUse graphicsUse = new RtGpuExecutor.TrackedGraphicsUse();

        PushSlot(GpuBuffer buffer) {
            this.buffer = buffer;
        }
    }
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private GpuImage sdrPresentImage;
    // DLSS Frame Generation: per-generated-frame interpolated output images (backbuffer size/format), and
    // the jitter-free reprojection matrices derived from the MV view-projections each frame. In HDR mode
    // these hold DLSSG's raw PQ-encoded output, which is blitted straight to the (PQ) swapchain — no decode
    // needed since the swapchain itself is PQ-native.
    private GpuImage[] fgInterp = new GpuImage[0];
    private int fgInterpW = -1;
    private int fgInterpH = -1;
    private int fgInterpFormat = Integer.MIN_VALUE;
    private boolean fgReset = true;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private GpuImage gNormal;
    private GpuImage gAlbedo;
    private GpuImage gDepth;
    private GpuImage gMotion;
    private GpuImage gSpecAlbedo;
    private GpuImage gSpecMotion;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private GpuImage rrOutput;
    /**
     * The two display-res images render passes rotate between as they chain post effects over the scene
     * (see {@code PassFrame.sceneColorTarget}). Separate from {@code rrOutput} so the reconstruction the
     * chain starts from stays readable and unmodified for auto-exposure metering and the debug scene view.
     */
    private GpuImage postColorA;
    private GpuImage postColorB;
    private final RtExposure exposure = new RtExposure();

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private float previousProceduralTime;
    private boolean proceduralTimeValid;
    private long atlasSampler;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private FrameSnapshot frameSnapshot;

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (WorldOverlayPass's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private RtGpuExecutor.GraphicsUse pendingGraphicsUse;

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for host overlay occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /** Read-only access to the auto-exposure controller, for diagnostics (F3 entry, frame stats log). */
    public RtExposure exposure() {
        return exposure;
    }

    /**
     * Export the latest RT scene image at the exact input seam of the Look/LMT stage.
     *
     * <p>The GPU image stores {@code sceneLinear * preExposure} in fp16. This readback multiplies RGB by
     * the display shader's current 1x1 {@code residualExposure}, in float32, then quantizes the resulting
     * exposure-adjusted scene-linear image to fp16 EXR. Metadata keeps both factors so the original scene-linear
     * values can be reconstructed with {@code RGB / (preExposure * residualExposure)}.
     *
     * @return {@code true} when a current RT frame was available and written
     */
    public boolean exportLatestResidualExposureExr(Path outputPath) throws java.io.IOException {
        GpuContext context = GpuContext.currentOrNull();
        if (context != null) {
            context.backend().assertRenderThread();
        }
        GpuContext ctx = GpuContext.currentOrNull();
        if (!enabled() || failed || ctx == null || rrOutput == null || exposure.image() == null
                || displayW <= 0 || displayH <= 0 || pendingGraphicsUse != null) {
            return false;
        }

        long pixelCount = Math.multiplyExact((long) displayW, (long) displayH);
        long rgbaBytes = Math.multiplyExact(pixelCount, 4L * Short.BYTES);
        long totalBytes = Math.addExact(rgbaBytes, Float.BYTES);
        if (pixelCount > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("EXR capture is too large for a Java array: "
                    + displayW + "x" + displayH);
        }

        // All ordinary frame commands have been submitted before the F2 key is handled. Drain them before
        // a private one-shot copy so rrOutput and the exposure image describe the same completed frame.
        ctx.waitIdle();
        GpuBuffer readback = ctx.createReadbackBuffer(totalBytes, "residual-exposure EXR readback");
        try {
            ctx.submitSync(cmd -> recordExrReadback(ctx, cmd, readback, rgbaBytes));
            readback.invalidate();

            float residualExposure = MemoryUtil.memGetFloat(readback.mapped + rgbaBytes);
            RtExposure.CaptureMetadata exposureMetadata = exposure.captureMetadata(residualExposure);
            short[] exposedRgba = new short[Math.toIntExact(pixelCount * 4L)];
            for (int sample = 0; sample < exposedRgba.length; sample++) {
                short storedHalf = MemoryUtil.memGetShort(readback.mapped + (long) sample * Short.BYTES);
                float value = Float.float16ToFloat(storedHalf);
                if ((sample & 3) != 3) {
                    value *= residualExposure;
                }
                // Residual exposure is expected to keep this seam comfortably centred in fp16. Clamp only
                // true outliers/infinities so a pathological light cannot poison a grading application.
                value = Math.clamp(value, -65504.0f, 65504.0f);
                exposedRgba[sample] = Float.floatToFloat16(value);
            }

            RtOpenExrWriter.write(outputPath, displayW, displayH, exposedRgba,
                    new RtOpenExrWriter.Metadata(
                            exposureMetadata.preExposure(),
                            exposureMetadata.residualExposure(),
                            exposureMetadata.absoluteExposure(),
                            exposureMetadata.mode(),
                            exposureMetadata.evScene(),
                            exposureMetadata.evTarget(),
                            exposureMetadata.evApplied(),
                            LOOK.id() + "@" + LOOK.packageVersion(),
                            frameCounter));
            return true;
        } finally {
            readback.destroy();
        }
    }

    private void recordExrReadback(GpuContext ctx, VkCommandBuffer cmd, GpuBuffer readback, long exposureOffset) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     "residual-exposure EXR readback")) {
            VkImageMemoryBarrier.Buffer imageBarriers = VkImageMemoryBarrier.calloc(2, stack);
            imageBarriers.get(0).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(rrOutput.image);
            imageBarriers.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            imageBarriers.get(1).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(exposure.image().image);
            imageBarriers.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarriers);

            VkBufferImageCopy.Buffer sceneCopy = VkBufferImageCopy.calloc(1, stack);
            sceneCopy.get(0).bufferOffset(0L);
            sceneCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            sceneCopy.get(0).imageExtent().set(displayW, displayH, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, rrOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    readback.handle, sceneCopy);

            VkBufferImageCopy.Buffer exposureCopy = VkBufferImageCopy.calloc(1, stack);
            exposureCopy.get(0).bufferOffset(exposureOffset);
            exposureCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            exposureCopy.get(0).imageExtent().set(1, 1, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, exposure.image().image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    readback.handle, exposureCopy);

            VkMemoryBarrier.Buffer hostBarrier = VkMemoryBarrier.calloc(1, stack);
            hostBarrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, hostBarrier, null, null);
        }
    }

    /**
     * Whether the current frame must retain source rasterization while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate or observe
     * the newly uploaded atlas. This prevents the source renderer from being suppressed before a deliberately
     * transient {@link #composite} return. Such a return is not a renderer failure and must not trip the host's
     * permanent safety latch.</p>
     */
    public boolean requiresSourceWorldFallback() {
        // Pipeline creation publishes a new material epoch and deliberately makes composite() return
        // false once so RtTerrain can apply the matching full clear. Keep source rasterization alive for that
        // bring-up frame; otherwise it is suppressed before composite() discovers it must fall back and the
        // host permanently latches the resulting missing replacement frame.
        if (worldPipeline == null || !materialBindingsReady) {
            return true;
        }
        if (materialEpochTraceGate) {
            return true;
        }
        if (RtEntityTextures.maxTextures() > bindlessTextureCapacity) {
            return true;
        }
        if (reloadRebindRequested) {
            long atlas = baseColorAtlasView;
            return atlas == 0L || atlas == boundBlockAlbedoAtlasHandle;
        }
        return false;
    }

    /**
     * Complete the material epoch while startup is retaining source presentation. The runtime calls this
     * only after the terrain update has applied the pending full clear, so activation depends on resource
     * readiness rather than an additional tick or rendered frame.
     */
    public boolean completeStartupBoundary() {
        materialEpochTraceGate = false;
        return !requiresSourceWorldFallback();
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on the source renderer until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
    }

    /** Capture the immutable host frame for the next composite. Called from the host render adapter. */
    public void captureFrame(FrameSnapshot snapshot) {
        frameSnapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** Reset exposure filtering after an explicit render-state invalidation such as F3+A. */
    public void resetExposureHistory() {
        exposure.requestReset();
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — host overlay raster passes reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Reset per-frame present state at the very start of host rendering. Critical for menu/no-world
     * frames: {@link #composite()} is only called while the host opens its world-composition window, so on
     * non-world frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
        frameSnapshot = null;
    }

    /** This frame's completion token, valid until {@link #finishGraphicsUse()} signals it. */
    public RtGpuExecutor.GraphicsUse currentGraphicsUse() {
        GpuContext context = GpuContext.currentOrNull();
        if (context != null) {
            context.backend().assertRenderThread();
        }
        return pendingGraphicsUse;
    }

    /**
     * Record every registered {@link RenderStage#OVERLAY} pass (currently just {@code WorldOverlayPass}) on
     * its own transient command buffer and submit it. Called once per frame from {@code GameRendererMixin}
     * at the post-upscale seam — this can't run inside the main {@link #composite} recording because the
     * world hasn't been upscaled yet at that point. No-op if RT hasn't run this frame ({@link #composite}
     * never reached {@link #ensureRenderPassManager}).
     */
    public void recordOverlayPasses() {
        if (renderPassManager == null) {
            return;
        }
        // A pass throwing is already isolated by RenderPassManager (disables that pass, logs, moves on);
        // this only guards the alloc/submit plumbing around it, so a transient device-lost-adjacent failure
        // here can't interrupt the rest of the frame either.
        try {
            GpuContext context = GpuContext.currentOrNull();
            if (context == null) {
                return;
            }
            GraphicsSubmission submission = context.backend().createGraphicsSubmission();
            VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
            renderPassManager.record(RenderStage.OVERLAY, cmd);
            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(overlay passes) failed");
            }
            submission.execute(cmd);
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Recording overlay render passes failed", t);
        }
    }

    /** Signal this RT frame's shared completion token after its final TLAS consumer (world overlay). */
    public void finishGraphicsUse() {
        RtGpuExecutor.GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before graphics use completed");
        }
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        ctx.gpuExecutor().endGraphicsUse(submission, graphicsUse);
        pendingGraphicsUse = null;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written
        if (failed) {
            return false;
        }
        GpuContext ctx = GpuContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Providers prepare their frame contributions before the ready gate below. A failing provider is
        // disabled and cleaned up independently, so another provider can continue serving the frame.
        ProviderManager.INSTANCE.prepareFrame();
        FrameSnapshot snapshot = frameSnapshot;
        if (RtTerrain.currentOrNull() == null || snapshot == null) {
            // No scene was captured this frame. Skip RT so the present path falls back to the host image.
            return false;
        }
        try {
            if (!ensurePresentationResources(ctx, snapshot.sceneId(), width, height)) {
                return false;
            }
            RtPipeline active = ensureWorld(ctx);
            if (active == null) {
                return false;
            }
            if (materialEpochTraceGate) {
                materialEpochTraceGate = false;
                return false;
            }
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion(snapshot);
            recordFrame(ctx, active, nativeColorImage, snapshot);
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to source rasterization", t);
            return false;
        }
    }

    /** Build every display-sized resource while startup is still presenting the source renderer. */
    public boolean ensurePresentationResourcesReady(GpuContext ctx, long sceneId, int width, int height) {
        if (failed || sceneId == 0L) {
            return false;
        }
        try {
            return ensurePresentationResources(ctx, sceneId, width, height);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT presentation resource bring-up failed; reverting to host presentation", t);
            return false;
        }
    }

    private boolean ensurePresentationResources(GpuContext ctx, long sceneId, int width, int height)
            throws IOException {
        if (renderPassSceneId != sceneId) {
            renderPassSceneId = sceneId;
            if (renderPassManager != null) {
                renderPassManager.invalidate();
            }
        }
        if (displayPipeline == null) {
            displayPipeline = RtDisplayPipeline.create(ctx);
        }
        ensureRenderPassManager(ctx);
        if (debugPresentPipeline == null) {
            debugPresentPipeline = RtDebugPresentPipeline.create(ctx);
        }
        if (sdrToneLut == null) {
            sdrToneLut = RtToneLut.load(ctx, "sdr_aces2_rec709.bin");
        }
        int wantedHdrNits = CausticaConfig.Rt.Hdr.PEAK_NITS.value();
        if (hdrToneLut == null || loadedHdrLutNits != wantedHdrNits) {
            RtToneLut newHdrLut = RtToneLut.load(ctx, "hdr_aces2_rec2020_" + wantedHdrNits + "nit.bin");
            if (newHdrLut.size != sdrToneLut.size) {
                newHdrLut.destroy();
                throw new IllegalStateException("SDR/HDR tone LUT size mismatch: "
                        + sdrToneLut.size + " vs " + newHdrLut.size);
            }
            if (hdrToneLut != null) {
                ctx.waitIdle();
                hdrToneLut.destroy();
            }
            hdrToneLut = newHdrLut;
            loadedHdrLutNits = wantedHdrNits;
        }
        if (lookLut == null) {
            lookLut = RtToneLut.loadResource(ctx, LOOK.lmtResource());
        }
        if (reloadRebindRequested) {
            long atlas = baseColorAtlasView;
            if (atlas == 0L || atlas == boundBlockAlbedoAtlasHandle) {
                return false;
            }
        }
        ensureOutput(ctx, width, height);
        debugPresentPipeline.setImages(displayImage.view, gNormal.view, gAlbedo.view, gDepth.view,
                gMotion.view, gSpecAlbedo.view, gSpecMotion.view, rrOutput.view, exposure.image().view,
                exposure.stateBuffer());
        exposure.ensureResources(ctx);
        refreshPipelineShapeIfNeeded(ctx);
        return true;
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so the immutable material snapshot is available to
     * the first worker section. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public boolean ensureResourcesReady(GpuContext ctx, SceneResources sceneResources) {
        baseColorAtlasView = sceneResources.baseColorAtlasView();
        if (failed || reloadRebindRequested) {
            return false;
        }
        if (worldPipeline != null) {
            return true;
        }
        if (!sceneResources.sceneReady() || baseColorAtlasView == 0L) {
            return false;
        }
        try {
            return ensureWorld(ctx) != null;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to source rasterization", t);
            return false;
        }
    }

    private RtPipeline ensureWorld(GpuContext ctx) throws IOException {
        if (worldPipeline == null) {
            if (!ensureWorldShadersReady()) {
                return null;
            }
            ensureRenderPassManager(ctx);
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            WorldShaders shaders = worldShaderBuild.shaders();
            worldPipeline = RtPipeline.create(ctx, new RtShaderCode[]{
                            shaders.primary(),
                            shaders.indirect()},
                    new RtShaderCode[]{shaders.skyMiss(), shaders.guideMiss()},
                    shaders.closestHit(),
                    shaders.radianceAnyHit(),
                    shaders.shadowAnyHit(),
                    WorldPushConstantsData.BYTE_SIZE, bindlessTextureCapacity,
                    worldShaderBuild.passResourceBindings());
            // Per-frame world data lives in this BDA ring; the pipeline pushes its address and hot fields.
            if (pushRing == null) {
                pushRing = new PushSlot[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = new PushSlot(ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i));
                }
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    private void ensureRenderPassManager(GpuContext ctx) throws IOException {
        if (renderPassManager == null) {
            renderPassManager = RenderPassManager.create(ctx, CausticaApi.registry().features(),
                    CausticaApi.options());
        }
    }

    private static WorldShaders compileWorldShaders(WorldShaderCompiler compiler, boolean reordered) {
        Composition composition = compiler.composition();
        var sky = composition.selection().binding(Slots.SKY);
        // Surfaces are per material rather than per composition, so the label names how many
        // implementations the dispatch switch fans out to, not one bound feature.
        int surfaceCount = composition.selection().surfaces().size();
        RtShaderCode primary = RtShaderCode.of("primary",
                compiler.compilePlain("primary.rgen.slang", WorldShaderCompiler.ENTRY_POINT));
        RtShaderCode indirect = RtShaderCode.of(
                "indirect(" + surfaceCount + " surfaces" + (reordered ? ", EXT_SER)" : ")"),
                compiler.compileIndirect(reordered));
        RtShaderCode skyMiss = RtShaderCode.of(
                "sky_miss(" + sky.feature().id() + ")", compiler.compileSkyMiss());
        RtShaderCode guideMiss = RtShaderCode.of("guide_miss",
                compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
        RtShaderCode closestHit = RtShaderCode.of(
                "closest_hit(" + surfaceCount + " surfaces)", compiler.compileClosestHit());
        RtShaderCode radianceAnyHit = RtShaderCode.of("radiance_any_hit",
                compiler.compilePlain("radiance_any_hit.rahit.slang", WorldShaderCompiler.ENTRY_POINT));
        RtShaderCode shadowAnyHit = RtShaderCode.of("shadow_any_hit",
                compiler.compilePlain("shadow_any_hit.rahit.slang", WorldShaderCompiler.ENTRY_POINT));
        WorldShaders shaders = new WorldShaders(primary, indirect, skyMiss, guideMiss, closestHit,
                radianceAnyHit, shadowAnyHit);
        CausticaMod.LOGGER.info("World shader composition active: sky={} ({}), surfaces={}, SER={}",
                sky.feature().id(), sky.binding().type(),
                composition.selection().surfaces().stream()
                        .map(implementation -> implementation.id().toString()).toList(),
                reordered ? "EXT" : "none");
        return shaders;
    }

    private record WorldShaders(RtShaderCode primary, RtShaderCode indirect, RtShaderCode skyMiss,
                                RtShaderCode guideMiss, RtShaderCode closestHit,
                                RtShaderCode radianceAnyHit, RtShaderCode shadowAnyHit) {
    }

    private record WorldShaderCacheKey(CausticaRegistry.Selection selection, boolean reordered) {
    }

    private record WorldShaderBuild(WorldShaderCacheKey key, WorldShaders shaders,
                                    Map<String, WorldShaderCompiler.PassResourceBinding> passResourceBindings) {
    }

    private record PendingWorldShaderBuild(WorldShaderCacheKey key,
                                           AtomicBoolean abandoned,
                                           CompletableFuture<WorldShaderBuild> future) {
    }

    private boolean ensureWorldShadersReady() {
        CausticaRegistry.Selection selection = CausticaApi.registry().selection();
        WorldShaderCacheKey key = new WorldShaderCacheKey(selection, RtDeviceBringup.serExtEnabled());
        if (worldShaderBuild != null) {
            if (worldShaderBuild.key().equals(key)) {
                return true;
            }
            releaseWorldShaders();
        }
        WorldShaderBuild cached = WORLD_SHADER_CACHE.get(key);
        if (cached != null) {
            abandonPendingWorldShaderBuild();
            worldShaderBuild = cached;
            CausticaMod.LOGGER.info("Reusing cached world shader composition");
            return true;
        }
        if (pendingWorldShaderBuild == null) {
            Path cache = Objects.requireNonNull(shaderCacheRoot,
                    "shader cache root was not configured by the host");
            CausticaMod.LOGGER.info("Preparing world shaders off thread");
            AtomicBoolean abandoned = new AtomicBoolean();
            pendingWorldShaderBuild = new PendingWorldShaderBuild(key, abandoned,
                    CompletableFuture.supplyAsync(
                            () -> buildWorldShaders(cache, key, abandoned),
                            SHADER_BUILD_EXECUTOR));
            return false;
        }
        if (!pendingWorldShaderBuild.key().equals(key)) {
            abandonPendingWorldShaderBuild();
            return false;
        }
        if (!pendingWorldShaderBuild.future().isDone()) {
            return false;
        }
        WorldShaderBuild build;
        try {
            build = pendingWorldShaderBuild.future().join();
        } catch (CompletionException e) {
            pendingWorldShaderBuild = null;
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("World shader compilation failed", cause);
        }
        pendingWorldShaderBuild = null;
        if (!build.key().equals(key)) {
            return false;
        }
        worldShaderBuild = build;
        return true;
    }

    private static WorldShaderBuild buildWorldShaders(Path cache, WorldShaderCacheKey key,
                                                       AtomicBoolean abandoned) {
        WorldShaderBuild cached = WORLD_SHADER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (abandoned.get()) {
            return null;
        }
        WorldShaderCompiler compiler = null;
        try {
            compiler = WorldShaderCompiler.createIsolated(cache, key.selection());
            if (abandoned.get()) {
                return null;
            }
            WorldShaders shaders = compileWorldShaders(compiler, key.reordered());
            WorldShaderBuild build = new WorldShaderBuild(key, shaders, compiler.passResourceBindings());
            WorldShaderBuild existing = WORLD_SHADER_CACHE.putIfAbsent(key, build);
            return existing != null ? existing : build;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare world shader sources", e);
        } finally {
            if (compiler != null) {
                compiler.close();
            }
        }
    }

    private void abandonPendingWorldShaderBuild() {
        if (pendingWorldShaderBuild != null) {
            pendingWorldShaderBuild.abandoned().set(true);
        }
        pendingWorldShaderBuild = null;
    }

    private void releaseWorldShaders() {
        worldShaderBuild = null;
    }

    private void refreshPipelineShapeIfNeeded(GpuContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        // Every stage is baked into this pipeline's SBT, so a slot selection change needs a complete
        // pipeline rebuild.
        boolean selectionChanged = worldShaderBuild != null
                && !worldShaderBuild.key().selection().equals(CausticaApi.registry().selection());
        if (selectionChanged) {
            ctx.waitIdle();
            worldPipeline.destroy();
            worldPipeline = null;
            releaseWorldShaders();
            bindlessTextureCapacity = 0;
            materialBindingsReady = false;
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle();
        worldPipeline.destroy();
        worldPipeline = null;
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the canonical material page bundles in reserved bindless slots. Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry, recreates material pages, builds
     * the shared material registry, and invalidates old-epoch geometry before tracing resumes.
     */
    private void bindWorldTextures(GpuContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = baseColorAtlasView;
        boundBlockAlbedoAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setBlockAlbedoAtlas(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        ProviderManager.MaterialContributions materials = ProviderManager.INSTANCE.collectMaterials();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.from(
                materials.rules(), CausticaApi.registry()::surfaceIndex);
        MaterialCatalog materialCatalog = RtRuntime.host().materialCatalog(materials.rules());
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, materialCatalog);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        worldPipeline.setEntityAlbedoTexture(0, atlasView, sampler);
        RtBlockMaterials.INSTANCE.bindPages(worldPipeline, sampler);
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialCatalog,
                materialOverrides, materials.definitions(), CausticaApi.registry()::surfaceIndex,
                bindlessTextureCapacity);
        sceneGeometry.invalidateMaterials();
        materialBindingsReady = true;
        bindPassResources();
        // Atlas UVs and material IDs are one resource epoch. Drop old terrain as a unit rather than
        // incrementally displaying old UVs/IDs against the new atlas/table.
        RtTerrain.requestFullClear();
        materialEpochTraceGate = true;
    }

    /**
     * Write every pass-published world resource the active composition's own Slang declared into the
     * world pipeline's set 2. These resources live for the device's lifetime but the pipeline's
     * descriptor sets do not, so this runs on every pipeline (re)creation as well as whenever the
     * published set changes. A published resource the current composition doesn't reference (a different
     * sky slot's Slang didn't import it) has no reflected index and is skipped — there is nothing in the
     * pipeline layout to write it into.
     */
    private void bindPassResources() {
        if (worldShaderBuild == null) {
            return;
        }
        Map<String, WorldShaderCompiler.PassResourceBinding> resourceBindings =
                worldShaderBuild.passResourceBindings();
        for (var entry : renderPassManager.worldResources().entrySet()) {
            WorldShaderCompiler.PassResourceBinding binding = resourceBindings.get(entry.getKey());
            if (binding == null) {
                continue;
            }
            RenderPassManager.WorldResource resource = entry.getValue();
            if (resource.buffer() != null) {
                worldPipeline.setPassResourceBuffer(binding.index(), resource.buffer().handle,
                        resource.buffer().size);
            } else {
                worldPipeline.setPassResource(binding.index(), resource.view(), resource.sampler());
            }
        }
        boundWorldResourceGeneration = renderPassManager.worldResourceGeneration();
    }

    /**
     * A pass may publish a world resource from {@code record()} — the celestials atlas does, because a
     * resource reload replaces its host handle and the pass has no create/resize call to republish from.
     * The final check runs after every pre-trace pass and before the first trace binds set 2. Handle
     * changes are rare, so draining the device is the cheap correct answer: set 2 is a single descriptor
     * set and an earlier frame may still be sampling it.
     */
    private void refreshPassResourcesIfNeeded(GpuContext ctx) {
        if (worldPipeline == null
                || renderPassManager.worldResourceGeneration() == boundWorldResourceGeneration) {
            return;
        }
        ctx.waitIdle();
        bindPassResources();
    }

    private void refreshMaterialBindingsIfNeeded(GpuContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /**
     * Called before the host re-stitches the block atlas and reloads entity textures. The host frees old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so the host can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). The new material epoch clears terrain before trace.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        materialBindingsReady = false;
        ProviderManager.INSTANCE.onResourceReload();
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx != null) {
            ctx.waitIdle();
            if (renderPassManager != null) {
                renderPassManager.invalidate();
            }
            if (worldPipeline != null) {
                worldPipeline.destroy();
                worldPipeline = null;
                bindlessTextureCapacity = 0;
            }
            RtMaterialRegistry.INSTANCE.destroy();
        }
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
        worldPipeline.setExtraStorageImage(5, gSpecMotion.view);
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
        if (postColorA != null) {
            postColorA.destroy();
            postColorA = null;
        }
        if (postColorB != null) {
            postColorB.destroy();
            postColorB = null;
        }
    }

    private void ensureOutput(GpuContext ctx, int width, int height) {
        // Debug presentation is downstream of the ordinary frame graph and must not change the image
        // being inspected. In particular, toggling it must not rebuild at native resolution or disable
        // the RR path whose render-resolution guide inputs the debug pass visualizes.
        boolean rrEnabled = RtDlssRr.configured();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && continuationQueue != null
                && displayImage != null && hdrDisplayImage != null && rrOutput != null
                && postColorA != null && postColorB != null
                && renderPassManager != null
                && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; DLSS-RR (or a fallback blit) upscales
        // to display res. With RR off there is no reconstruction pass, so trace at 1:1 for a faithful reference.
        // With RR on, ask NGX what render resolution its chosen quality mode actually expects rather
        // than assuming a fixed ratio: different quality modes (and driver versions) use different
        // ratios, and DLSSD's own optimal-settings query is the source of truth for what it will accept.
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;

        // RT traces and DLSS-RR reconstruct scene-linear ACEScg in an HDR R16G16B16A16_SFLOAT target,
        // so radiance > 1 and wide-gamut colour survive to the display seam. displayImage stays
        // R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        long pixelRecords = Math.multiplyExact((long) renderW, (long) renderH);
        long continuationBytes = Math.multiplyExact(
                Math.multiplyExact(pixelRecords, 2L), PATH_RECORD_BYTES);
        continuationQueue = ctx.createBuffer(continuationBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                "path continuation queue " + renderW + "x" + renderH + "x2");
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        // Two links are enough for a chain of any length: the reconstruction seeds it read-only, so the
        // passes alternate between these regardless of how many join.
        postColorA = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "post chain A " + width + "x" + height);
        postColorB = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "post chain B " + width + "x" + height);
        exposure.ensureResources(ctx);
        renderPassManager.resize(width, height);
        renderPassManager.setReconstructedColor(rrOutput);
        renderPassManager.setSceneColorTargets(postColorA, postColorB);
        renderPassManager.setExposureImage(exposure.image());
        displayPipeline.invalidateImages();

        mvHasPrev = false; // recreated images -> first MV frame is zero
        proceduralTimeValid = false;
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        debugPresentPipeline.setImages(displayImage.view, gNormal.view, gAlbedo.view, gDepth.view,
                gMotion.view, gSpecAlbedo.view, gSpecMotion.view, rrOutput.view, exposure.image().view,
                exposure.stateBuffer());
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion(FrameSnapshot snapshot) {
        snapshot.copyProjectionTo(frameProjection);
        snapshot.copyViewRotationTo(frameViewRotation);
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (snapshot.cameraX() - mvPrevCamX);
            mvCamDeltaY = (float) (snapshot.cameraY() - mvPrevCamY);
            mvCamDeltaZ = (float) (snapshot.cameraZ() - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = snapshot.cameraX();
        mvPrevCamY = snapshot.cameraY();
        mvPrevCamZ = snapshot.cameraZ();
        mvHasPrev = true;
    }

    private void recordFrame(GpuContext ctx, RtPipeline active, long nativeColorImage,
                             FrameSnapshot snapshot) {
        long dstImage = nativeColorImage;
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        // Reserve the graphics-use value that guards this frame's reusable TLAS and entity resources.
        RtGpuExecutor.GraphicsUse graphicsUse = gpuExecutor.beginGraphicsUse(submission);
        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        // Reuse a completed readback slot, then latch one pre-exposure value for both raygen and resolve.
        // This belongs after the timeline snapshot and before any world push data is written.
        exposure.beginFrame(graphicsUseWaiter);
        pendingGraphicsUse = graphicsUse;
        RtEntities.FrameEntities frameEntities = null;
        RtSceneGeometryManager.FrameGeometry providerGeometry = null;
        RtProviderLights.Frame providerLightFrame = null;
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        int debugView = debugView();
        RtTerrain terrain = RtTerrain.currentOrNull();
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // A debug view observes this ordinary path; it never changes jitter or disables RR.
            boolean rrPath = RtDlssRr.enabled();
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                RtJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = RtJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = RtJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            PushSlot selectedPushSlot = pushRing[pushSlot];
            graphicsUseWaiter.await(selectedPushSlot.graphicsUse);
            selectedPushSlot.graphicsUse.mark(graphicsUse);
            GpuBuffer pushBuf = selectedPushSlot.buffer;
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: camera-in-water (so the path tracer starts in the water medium when the eye is
            // submerged, fixing the air→water first-segment orientation) and animated water normals.
            // Bit 1 remains unused to avoid conflicting with stale external readers.
            int flags = snapshot.cameraInMedium() ? 0b01 : 0;
            if (waterWaves()) {
                flags |= 0b10000; // animated water wave normals
            }

            // The medium the eye itself is inside: the camera's own biome water colour, used only when
            // the camera starts submerged. Every hit takes its tint from its own primitive instead.
            FrameSnapshot.LinearRgb medium = snapshot.cameraMedium();
            Float3 cameraMedium = new Float3(medium.red(), medium.green(), medium.blue());
            float time = (float) (snapshot.timeSeconds() % 3600.0);
            float delta = time - previousProceduralTime;
            // A first frame, long pause, or one-hour phase wrap has no adjacent frame to reproject. Use
            // the current phase so a procedural surface reports no motion instead of a huge jump.
            float previousTime = proceduralTimeValid && delta >= 0f && delta <= 0.25f
                    ? previousProceduralTime : time;
            previousProceduralTime = time;
            proceduralTimeValid = true;
            // Procedural domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so a
            // pattern stays fixed in the world as the player moves and the rebase origin shifts.
            SceneOrigin sceneOrigin = new SceneOrigin(terrain.blockX, terrain.blockY, terrain.blockZ);
            providerLightFrame = providerLights.writeFrame(ctx,
                    ProviderManager.INSTANCE.frameLights(), sceneOrigin.x(), sceneOrigin.y(),
                    sceneOrigin.z(), graphicsUseWaiter);
            double proceduralPeriod = PROCEDURAL_ANCHOR_MASK + 1.0;
            Float2 proceduralDomainAnchor = new Float2(sceneOrigin.wrappedX(proceduralPeriod),
                    sceneOrigin.wrappedZ(proceduralPeriod));

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. The frame table
            // starts with retained terrain records and appends dynamic geometry records in the same index space.
            RtSceneGeometryManager.Capture geometryCapture = sceneGeometry.beginCapture();
            ProviderManager.INSTANCE.submitGeometry(geometryCapture::sink);
            providerGeometry = sceneGeometry.finishFrame(ctx, geometryCapture, terrain.staticInstances(),
                    terrain.geometryTablePrefix(), sceneOrigin);
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, providerGeometry.instances(),
                    providerGeometry.tablePrefix(), terrain.blockX, terrain.blockY, terrain.blockZ,
                    snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ(),
                    frameProjection, frameViewRotation);
            frameEntities = fe;
            BreakEntry[] breaking = breakingEntries(snapshot, terrain);
            new WorldPushData(
                    frameInvViewProj,
                    new Float3(sceneOrigin.relativeX(snapshot.cameraX()),
                            sceneOrigin.relativeY(snapshot.cameraY()),
                            sceneOrigin.relativeZ(snapshot.cameraZ())),
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    flags,
                    maxBounces(),
                    cameraMedium,
                    time,
                    proceduralDomainAnchor,
                    previousTime,
                    breaking.length,
                    mvCurProjView,
                    breaking,
                    // RIS emitter NEE: candidate count (0 = emitter NEE off; the shader also requires
                    // lightCount > 0, so an empty buffer leaves only direct-hit emission). The light buffer
                    // device addresses themselves are pc.light*Addr — every 64-bit address lives in the
                    // push-constant block now, not here.
                    new Float4(terrain.lightRebaseOffsetX(), terrain.lightRebaseOffsetY(),
                            terrain.lightRebaseOffsetZ(), terrain.lightInvGlobalPowerSum()),
                    new Float4(terrain.lightGridOriginX(), terrain.lightGridOriginY(), terrain.lightGridOriginZ(), 16f),
                    new Int4(terrain.lightGridDimX(), terrain.lightGridDimY(), terrain.lightGridDimZ(), 0),
                    terrain.lightCount(),
                    providerLightFrame.bufferAddress(),
                    providerLightFrame.lightCount(),
                    CausticaConfig.Rt.Lights.RIS_CANDIDATES.value(),
                    // Must be the SAME value the exposure resolve divides out this frame (it reads it
                    // from the same RtExposure accessor), or the two stop cancelling.
                    exposure.preExposure()
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Build the entity BLAS, the TLAS that references it and the terrain BLAS, then the trace.
            // Barriers separate each stage; the graphics-use timeline guards resource reuse.
            if (!providerGeometry.blasBuilds().isEmpty() || !fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.blasRecord")) {
                    if (!providerGeometry.blasBuilds().isEmpty()) {
                        RtAccel.recordBlasBuilds(ctx, cmd, providerGeometry.blasBuilds());
                    }
                    if (!fe.blas().isEmpty()) {
                        RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                    }
                }
                VulkanBarriers.memoryBarrier(cmd, stack); // provider/entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = sceneGeometry.prepareTlas(ctx, providerGeometry, fe.dynamicInstances(), graphicsUse);
            }
            active.setTlas(frameTlas.accel.handle, graphicsUse, graphicsUseWaiter);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Push the BDA ring slot's address plus the small hot subset used directly by the shaders.
            // Every 64-bit device address the trace needs lives here, not behind worldPushAddr: the
            // geometry/material tables are read from world.rahit/world.rchit, which never load
            // WorldPush at all, and the RIS light buffers are read from world.rgen's hot inner loop, so
            // none of them should cost an extra BDA dereference to find.
            ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
            new WorldPushConstantsData(pushBuf.deviceAddress, fe.geometryTableAddress(),
                    RtMaterialRegistry.INSTANCE.bindingTableAddress(),
                    RtMaterialRegistry.INSTANCE.surfaceTableAddress(),
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), continuationQueue.deviceAddress,
                    (int) frameCounter).write(pushConstants);
            renderPassManager.beginFrame();
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.skyLut")) {
                renderPassManager.record(RenderStage.ENVIRONMENT_PREPARE, cmd);
            }
            renderPassManager.record(RenderStage.BEFORE_TRACE, cmd);
            // PassFrame may publish a host-owned resource while recording either pre-trace stage. Resolve
            // those publications before this command buffer first binds set 2, including on the first
            // frame where a host atlas becomes available.
            refreshPassResourcesIfNeeded(ctx);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.tracePrimary")) {
                active.trace(cmd, renderW, renderH, pushConstants, 0);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // continuation/guide writes visible to the indirect trace
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.traceIndirect")) {
                active.trace(cmd, renderW, renderH, pushConstants, 1);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, gSpecMotion, rrOutput, renderW, renderH, displayW, displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            }

            // When DLSS-RR did not produce the display-res image (disabled or a runtime failure), bring
            // the render-res trace up to display res with a linear blit so the display mapper and
            // downstream debug pass always have a valid display-res scene image. With RR off
            // render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanBarriers.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // rrOutput visible to exposure histogram

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                exposure.record(ctx, cmd, stack, rrOutput, gDepth, gAlbedo);
                exposure.recordStateReadback(cmd, stack);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // exposure image visible to downstream passes

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "post chain");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.postChain")) {
                renderPassManager.record(RenderStage.AFTER_RECONSTRUCTION, cmd);
            }
            renderPassManager.record(RenderStage.LOOK, cmd);

            // Only now is it known which image the post chain left the scene in: participation is decided
            // inside each pass's record(). Rebinding here is a no-op unless the set of chained passes
            // changed, and it precedes the descriptor's own bind inside dispatch().
            RtToneLut displayLookLut = lookLut;
            displayPipeline.setImages(displayImage.view, renderPassManager.sceneColor().view,
                    exposure.image().view, hdrDisplayImage.view,
                    sdrToneLut.view(), sdrToneLut.sampler(), hdrToneLut.view(), hdrToneLut.sampler(),
                    displayLookLut.view(), displayLookLut.sampler(), graphicsUse, graphicsUseWaiter);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        sdrToneLut.size, CausticaConfig.Rt.Tonemap.GAMMA.value(), loadedHdrLutNits,
                        true, lookLut.size);
            }
            hdrWrittenThisFrame = CausticaConfig.Rt.Hdr.enabled();
            VulkanBarriers.memoryBarrier(cmd, stack); // display output visible to debug composite

            if (debugView != 0) {
                // Debug content is composited only after the real scene has completed trace, RR/fallback,
                // exposure, and display mapping. It therefore observes the renderer without perturbing
                // exposure history or feeding literal diagnostic colors through ACES. Debug presentation
                // remains SDR for now; a PQ swapchain uses the existing SDR->PQ conversion path.
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "debug present");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.debugPresent")) {
                    debugPresentPipeline.dispatch(cmd, displayW, displayH, debugView,
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_SIGMA.value(),
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_FLOOR.value());
                }
                hdrWrittenThisFrame = false;
            }
            VulkanBarriers.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanBarriers.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        submission.execute(cmd);
        // Do not attach a merely reserved token: failed recording may never signal it. Once execute succeeds,
        // every owner in this frame's manifest is protected through the final overlay consumer.
        RtEntities.INSTANCE.markGraphicsUse(frameEntities, graphicsUse);
        sceneGeometry.markGraphicsUse(providerGeometry, ctx, graphicsUse);
        providerLights.markGraphicsUse(providerLightFrame, graphicsUse);
        exposure.markStateReadbackUse(graphicsUse);
    }

    /** Rebase this frame's host-authored damage overlays into the shader push array. */
    private BreakEntry[] breakingEntries(FrameSnapshot snapshot, RtTerrain terrain) {
        BreakEntry[] result = new BreakEntry[WorldPushData.BREAKING_CAPACITY];
        int count = 0;
        for (DamageOverlay overlay : snapshot.damageOverlays()) {
            if (count >= result.length) {
                break;
            }
            result[count++] = new BreakEntry(new Int4(
                    overlay.worldX() - terrain.blockX,
                    overlay.worldY() - terrain.blockY,
                    overlay.worldZ() - terrain.blockZ,
                    overlay.textureSlot()));
        }
        return count == result.length ? result : java.util.Arrays.copyOf(result, count);
    }


    public void destroy() {
        // Session teardown stops the GPU executor and waits the device idle before entering here, so the
        // TLAS ring's slots are no longer in flight and can be freed immediately.
        sceneGeometry.shutdown();
        providerLights.destroy();
        RtDlssRr.INSTANCE.destroy();
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        if (fgHudlessImage != null) {
            fgHudlessImage.destroy();
            fgHudlessImage = null;
        }
        if (fgHdrHudlessImage != null) {
            fgHdrHudlessImage.destroy();
            fgHdrHudlessImage = null;
        }
        // WorldOverlayPass's features/pipelines/scratch are torn down by renderPassManager.destroy() below,
        // as a registered RenderStage.OVERLAY pass.
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();
        exposure.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (renderPassManager != null) {
            renderPassManager.destroy();
            renderPassManager = null;
        }
        renderPassSceneId = Long.MIN_VALUE;
        if (debugPresentPipeline != null) {
            debugPresentPipeline.destroy();
            debugPresentPipeline = null;
        }
        if (sdrToneLut != null) {
            sdrToneLut.destroy();
            sdrToneLut = null;
        }
        if (hdrToneLut != null) {
            hdrToneLut.destroy();
            hdrToneLut = null;
        }
        if (lookLut != null) {
            lookLut.destroy();
            lookLut = null;
        }
        loadedHdrLutNits = -1;
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            GpuContext hdrCtx = GpuContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        for (GpuImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new GpuImage[0];
        fgInterpW = -1;
        fgInterpH = -1;
        fgInterpFormat = Integer.MIN_VALUE;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        RtBlockMaterials.INSTANCE.reset();
        abandonPendingWorldShaderBuild();
        releaseWorldShaders();
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        materialEpochTraceGate = false;
        RtMaterialRegistry.INSTANCE.destroy();
        if (pushRing != null) {
            for (PushSlot slot : pushRing) {
                if (slot != null) {
                    slot.buffer.destroy();
                }
            }
            pushRing = null;
        }
        if (atlasSampler != 0L) {
            GpuContext ctx = GpuContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
        reloadRebindRequested = false;
        boundBlockAlbedoAtlasHandle = 0L;
        boundWorldResourceGeneration = -1;
        displayW = -1;
        displayH = -1;
        renderW = -1;
        renderH = -1;
        renderSizeRrEnabled = false;
        renderSizeRrQuality = Integer.MIN_VALUE;
        fgReset = true;
        mvHasPrev = false;
        proceduralTimeValid = false;
        failed = false;
        loggedActive = false;
        frameSnapshot = null;
        currentTlasHandle = 0L;
        pendingGraphicsUse = null;
        hdrWrittenThisFrame = false;
    }

    private long atlasSampler(GpuContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the host SDR blit. */
    public boolean isHdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * DLSS-FG: the PQ-encoded HDR backbuffer (view/image), valid only right after {@link #presentHdr} has run
     * this frame (it's the same image {@code presentHdr} just composited UI into and blitted to the
     * swapchain) — used as the interpolation source for HDR frame generation instead of the SDR main target.
     * Already display-ready PQ, so it's fed to DLSSG directly with no extra encode step. 0 if HDR isn't
     * active this frame.
     */
    public long hdrBackbufferView() {
        return hdrDisplayImage != null ? hdrDisplayImage.view : 0L;
    }

    public long hdrBackbufferImage() {
        return hdrDisplayImage != null ? hdrDisplayImage.image : 0L;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing the host SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link GpuImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror the host blit exactly. Y is flipped to match the host swapchain blit.
     */
    public void presentHdr(GraphicsSubmission submission, long swapchainImage, int swapW, int swapH,
                           long acquireSem, long presentSem, UiPresentationResources ui) {
        GpuImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = submission.beginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = ui.populated() ? ui.colorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.uiNits());
                }
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like the host path.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            submission.waitSemaphore(acquireSem, 0L, 65536L);
            submission.execute(cmd);
            submission.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        GpuContext ctx = GpuContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(GpuContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of the host's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        // The conversion is needed only while the CURRENT swapchain is PQ and this frame has no HDR
        // image (menus/loading, or the short interval after the toggle changed but before configure()).
        // Once configure recreates a native-SDR swapchain, the host's ordinary blit is correct.
        return CausticaConfig.Rt.Hdr.swapchainPqActive()
                && RtRuntime.hasSession()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep the host SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(GraphicsSubmission submission, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (!RtRuntime.hasSession() || sdrMainView == 0L || failed) {
            return false;
        }
        GpuContext ctx = GpuContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        GpuImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = submission.beginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.uiNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like the host path.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            submission.waitSemaphore(acquireSem, 0L, 65536L);
            submission.execute(cmd);
            submission.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Linear-filtered blit of the full render-res image into the full display-res image. Used as the
     * non-RR / fallback upscale so display mapping always sees a display-res RT image; a no-op stretch when
     * the two are the same size (RR disabled -> render == display).
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, GpuImage src, GpuImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

    /**
     * DLSS Frame Generation quality: capture a copy of {@code main} (the main render target) into
     * {@link #fgHudlessImage} for {@link #fgInterpolate} to feed DLSSG as the "hudless" resource. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * the host composites its UI layer. At that point, when the UI redirect is active, {@code main} still
     * has no combined UI baked in. No-op (and {@link #fgInterpolate} passes 0/0/0 for hudless, same as always)
     * unless both FG and the UI overlay redirect are active — capturing this without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(long sourceImage, int width, int height, UiPresentationResources ui) {
        if (!RtDlssFg.enabled() || !ui.enabled() || sourceImage == 0L) {
            return;
        }
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHudlessImage == null || fgHudlessImage.width != width || fgHudlessImage.height != height) {
            if (fgHudlessImage != null) {
                fgHudlessImage.destroy();
            }
            fgHudlessImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture " + width + "x" + height);
        }
        GraphicsSubmission submission = ctx.backend().createGraphicsSubmission();
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanBarriers.memoryBarrier(cmd, stack);
            VK10.vkCmdCopyImage(cmd, sourceImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    fgHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
            VulkanBarriers.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        submission.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into {@link #fgHdrHudlessImage} for {@link
     * #fgInterpolate}'s HDR path to feed DLSSG as the "hudless" resource. A plain copy, not a format
     * conversion: both images are
     * already PQ-encoded (the display-ready EOTF-encoded [0,1] signal DLSS-FG's programming guide requires),
     * so no encode step is needed. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, GpuImage src) {
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHdrHudlessImage == null || fgHdrHudlessImage.width != src.width || fgHdrHudlessImage.height != src.height) {
            if (fgHdrHudlessImage != null) {
                fgHdrHudlessImage.destroy();
            }
            fgHdrHudlessImage = ctx.createStorageImage(src.width, src.height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + src.width + "x" + src.height);
        }
        // Make composite()'s writes to hdrDisplayImage (an earlier submit this frame) visible to this copy;
        // the copy's write is then made visible to the UI-composite dispatch that follows (and to DLSSG's
        // read, in a later command buffer) by the same idiom.
        VulkanBarriers.memoryBarrier(cmd, stack);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                fgHdrHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, src.width, src.height));
        VulkanBarriers.memoryBarrier(cmd, stack);
    }

    /**
     * DLSS Frame Generation: record the DLSSG evaluate for generated frame {@code index} of {@code count}
     * (backbuffer = the final frame; HW depth = {@code gDepth}; motion = {@code gMotion}) into the host
     * command encoder, returning the interpolated output image (backbuffer size) for {@link RtFramePresenter}
     * to blit into a generated swapchain image. On {@code index == 1} it ensures the feature (created in its
     * own synchronous submit), the per-index output images, and the jitter-free reprojection matrices.
     * Returns {@code null} (caller falls back to duplicating the real frame for this one frame, no session
     * impact) when there's simply no captured RT frame to interpolate from right now — routine and expected
     * on menu/loading/transition frames, since {@link RtFramePresenter#isActive} only gates on being in a
     * world, not on RT having actually produced a frame this tick. Throws instead for failures that should
     * never happen once RT is actively producing frames (DLSSG feature creation failing, an out-of-range
     * index, the evaluate itself failing) — the caller treats those as fatal and disables FG for the
     * session, same as any other FG present-record failure, rather than silently degrading to duplicated
     * (non-interpolated) frames forever with no visible sign anything is wrong. Rotation-only matrices;
     * camera translation is carried by the mvecs (cameraMotionIncluded).
     *
     * <p>{@code hdrBackbuffer} selects the HDR path. Per the DLSS-FG programming guide's HDR section, scRGB is
     * explicitly unsupported as a DLSS-FG input ("not suitable as inputs to DLSS-FG" — it wants a
     * display-ready, EOTF-encoded [0,1] signal, recommending HDR10/ST.2084) — since the renderer's whole HDR
     * pipeline is natively PQ-encoded, every image fed to {@code RtDlssFg.evaluate} in HDR mode is already in
     * that format with no extra conversion needed: the backbuffer is the raw {@code backbufferView}/
     * {@code backbufferImage} the caller passed in ({@link #hdrBackbufferView()}, already PQ + UI-composited
     * by {@link #presentHdr}); the hudless resource is {@link #fgHdrHudlessImage} (copied by {@link
     * #presentHdr} <em>before</em> its own UI composite ran, mirroring {@link #captureFgHudless}'s pre-UI
     * timing); and DLSSG's own (also PQ-encoded) output is returned as-is, since the swapchain itself is
     * PQ-native and can blit it directly. The UI resource itself needs no HDR-specific handling — it's the
     * same combined host UI texture used by both present paths; only the compositing math differs.
     */
    public GpuImage fgInterpolate(GraphicsSubmission submission, long backbufferView, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer,
            UiPresentationResources ui) {
        if (failed || gDepth == null || gMotion == null || frameSnapshot == null) {
            return null;
        }
        GpuContext ctx = GpuContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        final int fmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (index == 1) {
            if (!ensureFgFeature(ctx, swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("DLSSG feature not ready (ensureFgFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            // clipToPrevClip = prevVP * inverse(curVP); prevClipToClip = curVP * inverse(prevVP). Both from
            // the (rotation-only, camera-relative) MV view-projections, so jitter-free.
            fgMatTmp.set(mvCurProjView).invert();
            fgClipToPrev.set(mvPrevProjView).mul(fgMatTmp);
            fgMatTmp.set(mvPrevProjView).invert();
            fgPrevToClip.set(mvCurProjView).mul(fgMatTmp);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        GpuImage out = fgInterp[index - 1];
        // Only feed hudless/ui when they exist AND match this frame's backbuffer size — a stale or mismatched
        // size (e.g. mid-resize) is worse than skipping, so fall back to 0/0/0 (DLSSG just does without).
        GpuImage hudlessSrc = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
        boolean hudlessReady = hudlessSrc != null && hudlessSrc.width == swapW && hudlessSrc.height == swapH;
        long hudlessView = hudlessReady ? hudlessSrc.view : 0L;
        long hudlessImg = hudlessReady ? hudlessSrc.image : 0L;
        int hudlessFmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = ui.width() == swapW && ui.height() == swapH
                && ui.colorView() != 0L && ui.colorImage() != 0L;
        long uiView = uiReady ? ui.colorView() : 0L;
        long uiImg = uiReady ? ui.colorImage() : 0L;

        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        boolean ok = RtDlssFg.INSTANCE.evaluate(cmd.address(),
                backbufferView, backbufferImage, fmt,
                gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                out.view, out.image, fmt,
                swapW, swapH, renderW, renderH, count, index, 1.0f, 1.0f,
                true /* depthInverted (reversed-Z) */, hdrBackbuffer /* colorBuffersHDR */,
                true /* cameraMotionIncluded (in mvecs) */, fgReset,
                fgClipToPrev, fgPrevToClip);
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        fgReset = false;
        if (!ok) {
            throw new IllegalStateException("ngxshim_evaluate_dlssg failed (RtDlssFg.evaluate returned false)");
        }
        submission.execute(cmd);
        return out;
    }

    private boolean ensureFgFeature(GpuContext ctx, int w, int h, int rw, int rh, int fmt) {
        if (RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt)) {
            return true;
        }
        // Create the feature in its own submit + wait (not folded into MC's frame submit).
        ctx.submitSync(c -> RtDlssFg.INSTANCE.ensureFeature(c.address(), w, h, rw, rh, fmt));
        fgReset = true; // fresh feature has no temporal history
        return RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt);
    }

    private void ensureFgInterp(GpuContext ctx, int count, int w, int h, int fmt) {
        if (fgInterp.length == count && fgInterpW == w && fgInterpH == h && fgInterpFormat == fmt
                && (count == 0 || fgInterp[0] != null)) {
            return;
        }
        for (GpuImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new GpuImage[count];
        for (int i = 0; i < count; i++) {
            fgInterp[i] = ctx.createStorageImage(w, h, fmt, "FG interp " + i + " " + w + "x" + h);
        }
        fgInterpW = w;
        fgInterpH = h;
        fgInterpFormat = fmt;
    }
}
