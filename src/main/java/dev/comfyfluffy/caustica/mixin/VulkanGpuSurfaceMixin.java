package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftHdr;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.MinecraftVulkanImageBorrow;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.LongBuffer;
import java.util.List;

/**
 * HDR capability logging and PQ swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When HDR is enabled and the surface advertises HDR10_ST2084 (paired with whatever pixel format the
 * surface offers for it), we steer Minecraft's swapchain to it. When HDR is disabled, configure selects
 * vanilla's native SDR pair. The options callback invalidates the surface configuration, so toggling HDR
 * reuses the same swapchain recreation path as resize.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	@Final
	private org.lwjgl.vulkan.VkQueue presentQueue;

	@Shadow
	private long swapchain;

	@Shadow
	@Final
	@Mutable
	private int swapchainImageFormat;

	@Shadow
	@Final
	private LongList swapchainImages;

	@Shadow
	private int currentImageIndex;

	@Shadow
	private int swapchainWidth;

	@Shadow
	private int swapchainHeight;

	@Shadow
	@Final
	private long[] acquireSemaphores;

	@Shadow
	private int currentAcquireSemaphore;

	@Shadow
	private long[] presentSemaphores;

	@Unique
	private int caustica$colorSpace = 0;
	@Unique
	private MinecraftVulkanImageBorrow caustica$sdrPresentationSource;

	@Unique
	private long caustica$metadataSwapchain;

	@Unique
	private int caustica$metadataPeakNits = -1;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void caustica$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			MinecraftHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/** Discover PQ capability during construction and select PQ only when HDR starts enabled. */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void caustica$pickPqFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		VkSurfaceFormatKHR pq = caustica$findPq(formats);
		CausticaConfig.Rt.Hdr.setSwapchainPqAvailable(pq != null);
		this.caustica$colorSpace = 0;
		CausticaConfig.Rt.Hdr.setSwapchainPqActive(false);
		if (CausticaClientComposition.current().runtime().wantsPqSwapchain() && pq != null) {
			this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
			CausticaConfig.Rt.Hdr.setSwapchainPqActive(true);
			CausticaMod.LOGGER.info("HDR: surface supports PQ (format={}, colorSpace=HDR10_ST2084); "
					+ "creating the initial swapchain in PQ", pq.format());
			cir.setReturnValue(pq);
		}
	}

	/**
	 * A framebuffer resize already recreates the swapchain through {@code configure}. Refresh the chosen
	 * (format,colorSpace) pair at that same boundary so the HDR option can use the identical path without
	 * recreating the window or Vulkan surface. Vanilla made swapchainImageFormat final because resize
	 * normally keeps it fixed; the mixin marks that field mutable specifically for this re-selection.
	 */
	@Inject(method = "configure", at = @At("HEAD"))
	private void caustica$refreshFormatForConfigure(GpuSurface.Configuration config, CallbackInfo ci) {
		try {
			List<MinecraftHdr.SurfaceFormat> formats = MinecraftHdr.surfaceFormats(
					this.device.vkDevice().getPhysicalDevice(), this.surface);
			MinecraftHdr.SurfaceFormat pq = caustica$findPqValue(formats);
			MinecraftHdr.SurfaceFormat sdr = caustica$findSdrValue(formats);
			CausticaConfig.Rt.Hdr.setSwapchainPqAvailable(pq != null);
			boolean usePq = CausticaClientComposition.current().runtime().wantsPqSwapchain() && pq != null;
			if (!usePq && sdr == null && pq != null) {
				// Extremely unusual, but safer than destroying the only viable presentation path.
				CausticaMod.LOGGER.warn("HDR: surface exposes PQ but no compatible native-SDR format; "
						+ "keeping the PQ swapchain and embedding SDR content");
				usePq = true;
			}
			if (usePq) {
				this.swapchainImageFormat = pq.format();
				this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
			} else {
				if (sdr == null) {
					CausticaMod.LOGGER.warn("HDR: surface exposes no compatible SDR or PQ format during recreation");
					return;
				}
				this.swapchainImageFormat = sdr.format();
				this.caustica$colorSpace = 0;
			}
			CausticaConfig.Rt.Hdr.setSwapchainPqActive(usePq);
			CausticaMod.LOGGER.info("HDR: recreating swapchain as {} (format={}, colorSpace={})",
					usePq ? "PQ" : "native SDR", this.swapchainImageFormat,
					usePq ? "HDR10_ST2084" : "SRGB_NONLINEAR");
		} catch (RuntimeException failure) {
			CausticaMod.LOGGER.warn("HDR: failed to enumerate swapchain formats during recreation", failure);
		}
	}

	@Unique
	private static MinecraftHdr.SurfaceFormat caustica$findPqValue(List<MinecraftHdr.SurfaceFormat> formats) {
		return formats.stream().filter(format -> format.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT)
				.findFirst().orElse(null);
	}

	@Unique
	private static MinecraftHdr.SurfaceFormat caustica$findSdrValue(List<MinecraftHdr.SurfaceFormat> formats) {
		return formats.stream().filter(format -> format.colorSpace() == 0
				&& (format.format() == 37 || format.format() == 44)).findFirst().orElse(null);
	}

	@Unique
	private static VkSurfaceFormatKHR caustica$findPq(VkSurfaceFormatKHR.Buffer formats) {
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
				return f;
			}
		}
		return null;
	}

	@Unique
	private static VkSurfaceFormatKHR caustica$findSdr(VkSurfaceFormatKHR.Buffer formats) {
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == 0 && (f.format() == 37 || f.format() == 44)) {
				return f;
			}
		}
		return null;
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the PQ color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int caustica$overrideColorSpace(int original) {
		return this.caustica$colorSpace != 0 ? this.caustica$colorSpace : original;
	}

	/**
	 * Chain {@code VkSwapchainLatencyCreateInfoNV{latencyModeEnable=true}} into the swapchain's pNext at
	 * creation. {@code vkSetLatencySleepModeNV} only takes effect on a swapchain created with this flag,
	 * so it has to be set here,
	 * before there's any other reason to touch swapchain creation. Preserves whatever pNext was already
	 * there (currently nothing else chains one). The extra struct is stack-allocated and only needs to
	 * survive this call — Vulkan reads pNext chains synchronously during {@code vkCreateSwapchainKHR}, it
	 * doesn't retain the pointer afterward, so freeing it when this method's stack frame pops is safe even
	 * though {@code pCreateInfo} isn't touched again after this point in {@code configure()}. No-op (calls
	 * through unchanged) when Reflex isn't enabled + device-supported.
	 */
	@Redirect(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int caustica$createSwapchainWithReflex(VkDevice device, VkSwapchainCreateInfoKHR pCreateInfo,
			VkAllocationCallbacks pAllocator, LongBuffer pSwapchain) {
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		if (backend == null || !backend.capabilities().lowLatency()) {
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default();
			latency.pNext(pCreateInfo.pNext());
			latency.latencyModeEnable(true);
			pCreateInfo.pNext(latency.address());
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
	}

	/**
	 * Reapply the Reflex sleep-mode config for the configured swapchain. The configuration
	 * is scoped to a specific swapchain object, so it must be re-called whenever {@code configure()} builds a
	 * new one (e.g. resize); applying unchanged settings is an idempotent no-op.
	 * it unconditionally here is cheap. No-op when Reflex isn't enabled + device-supported.
	 */
	@Inject(method = "configure", at = @At("TAIL"))
	private void caustica$applySwapchainExtensionState(GpuSurface.Configuration config, CallbackInfo ci) {
		caustica$applyHdrMetadataIfNeeded();
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		if (backend != null && backend.capabilities().lowLatency()) {
			backend.lowLatency().applySleepMode(this.device.vkDevice(), this.swapchain);
		}
		// DLSS-FG diagnostic: MAILBOX/IMMEDIATE present modes let a later present silently replace/skip an
		// earlier queued-but-not-yet-scanned-out one, which would drop FG's generated frame before the
		// display ever shows it — even though our vkQueuePresentKHR call itself reports success. FIFO is the
		// only mode that guarantees every queued present gets its own vblank. Log once per (re)configure so
		// this is checkable without guessing at the in-game V-Sync setting.
		if (CausticaClientComposition.current().runtime().frameGenerationActive(Minecraft.getInstance().level != null)) {
			CausticaMod.LOGGER.info("DLSS-FG: swapchain present mode = {} (FIFO required for generated frames "
					+ "to actually display; MAILBOX/IMMEDIATE will silently drop them — enable V-Sync if not FIFO)",
					config.presentMode());
		}
	}

	/**
	 * Emit PRESENT_START/END markers around the real frame's present and, when
	 * {@code VK_KHR_present_id} is enabled) chaining a {@code VkPresentIdKHR} onto it so the marker's
	 * {@code presentID} correlates with this exact present call. The FG-generated extra presents
	 * (RT-generated frames) are deliberately NOT marked/present-id'd — Reflex paces/measures the real
	 * frame only. No-op passthrough unless Reflex has successfully applied sleep mode for this swapchain.
	 */
	@Redirect(method = "present",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int caustica$presentWithReflex(VkQueue queue, VkPresentInfoKHR presentInfo) {
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		VulkanLowLatency lowLatency = backend == null ? null : backend.lowLatency();
		boolean reflexActive = lowLatency != null && lowLatency.active()
				&& this.swapchain == lowLatency.appliedSwapchain();
		if (!reflexActive) {
			return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}
		VkDevice vkDevice = this.device.vkDevice();
		// Own counter (not currentSimFrameId()): Minecraft can present outside the normal tick loop (e.g.
		// Minecraft.setScreenAndShow's synchronous redraw when opening a world), so presentID must advance on
		// every actual vkQueuePresentKHR call, not just once per sleep()/runTick — otherwise a stale, already-
		// used id gets resent and VUID-VkPresentIdKHR-presentIds-04999 fires.
		long presentId = lowLatency.advancePresentId();
		lowLatency.marker(vkDevice, this.swapchain, VulkanLowLatency.RENDER_SUBMIT_END, presentId);
		lowLatency.marker(vkDevice, this.swapchain, VulkanLowLatency.PRESENT_START, presentId);
		int result;
		if (backend.capabilities().presentIds()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPresentIdKHR vkPresentId = VkPresentIdKHR.calloc(stack).sType$Default()
						.pNext(presentInfo.pNext())
						.swapchainCount(1)
						.pPresentIds(stack.longs(presentId));
				presentInfo.pNext(vkPresentId.address());
				result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
			}
		} else {
			result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}
		lowLatency.marker(vkDevice, this.swapchain, VulkanLowLatency.PRESENT_END, presentId);
		return result;
	}

	/**
	 * HDR present path. When the RT renderer has a fresh PQ image and the swapchain is PQ, composite the
	 * SDR-authored UI and blit the result directly into the swapchain instead of Minecraft's SDR main target.
	 *
	 * <p>Because this cancels {@code blitFromTexture} at HEAD, the normal {@code caustica$prepareGeneratedFrame}
	 * TAIL inject below never runs on HDR frames — so DLSS-FG's extra-present step is invoked explicitly here,
	 * right after the real HDR frame is recorded, using the just-composited {@code hdrDisplayImage} (already
	 * UI-composited by {@code presentHdr}) as the interpolation source instead of the SDR main target.
	 */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void caustica$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		// The mastering peak is a live option and selects a different baked ACES output LUT without forcing
		// swapchain recreation. Refresh the metadata once when that selected LUT changes.
		caustica$applyHdrMetadataIfNeeded();
		if (this.currentImageIndex < 0) {
			return;
		}
		RtRuntime presentation = CausticaClientComposition.current().runtime();
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		long acquireSem = this.acquireSemaphores[this.currentAcquireSemaphore];
		long presentSem = this.presentSemaphores[this.currentImageIndex];
		if (CausticaClientComposition.current().runtime().isHdrPresentActive()) {
			VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
			GraphicsSubmission submission = MinecraftVulkanBackend.wrap(enc);
			UiPresentationResources ui = CausticaClientComposition.current().uiOverlay().capturePresentation();
			presentation.presentHdr(submission, swapchainImage, this.swapchainWidth, this.swapchainHeight,
					acquireSem, presentSem, ui);
			if (ui.populated() && ui.colorView() != 0L) {
				CausticaClientComposition.current().uiOverlay().markConsumed();
			}
			caustica$prepareGeneratedFrameHdr(submission, ui);
			ci.cancel();
			return;
		}
		// Non-RT frame (menu, title panorama, loading screen) on a PQ swapchain: vanilla's raw SDR blit would
		// misdisplay (SDR bytes reinterpreted as PQ codes). Convert sRGB -> PQ at paper white instead. Falls
		// through to vanilla SDR if conversion resources aren't ready or the source view is not a Vulkan view.
		if (CausticaClientComposition.current().runtime().isPqSdrPresentActive()) {
			VulkanDeviceContext gpu = CausticaClientComposition.current().runtime().vulkanContextOrNull();
			if (gpu != null && textureView instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView view
					&& view.texture().getFormat() == com.mojang.blaze3d.GpuFormat.RGBA8_UNORM) {
				if (caustica$sdrPresentationSource == null || !caustica$sdrPresentationSource.wraps(
						gpu, view, this.swapchainWidth, this.swapchainHeight)) {
					MinecraftVulkanImageBorrow old = caustica$sdrPresentationSource;
					caustica$sdrPresentationSource = MinecraftVulkanImageBorrow.sampled(gpu, view,
							this.swapchainWidth, this.swapchainHeight, VK10.VK_FORMAT_R8G8B8A8_UNORM);
					if (old != null) gpu.retireAfterUse(old::destroy);
				}
				if (presentation.presentSdrToPq(
					MinecraftVulkanBackend.wrap((VulkanCommandEncoder) commandEncoder), swapchainImage,
					this.swapchainWidth, this.swapchainHeight, caustica$sdrPresentationSource,
					acquireSem, presentSem)) {
				ci.cancel();
				}
			}
		}
	}

	@Unique
	private void caustica$applyHdrMetadataIfNeeded() {
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		if (this.caustica$colorSpace != VK_COLOR_SPACE_HDR10_ST2084_EXT || backend == null
				|| !backend.capabilities().hdrMetadata() || this.swapchain == 0L) {
			return;
		}
		int peakNits = CausticaConfig.Rt.Hdr.PEAK_NITS.value();
		if (this.caustica$metadataSwapchain == this.swapchain
				&& this.caustica$metadataPeakNits == peakNits) {
			return;
		}
		if (MinecraftHdr.applyMasteringMetadata(this.device.vkDevice(), this.swapchain, peakNits)) {
			this.caustica$metadataSwapchain = this.swapchain;
			this.caustica$metadataPeakNits = peakNits;
		}
	}

	@Unique
	private static long caustica$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/**
	 * After Minecraft blits the real frame into its acquired swapchain image, evaluate DLSS Frame Generation
	 * (but before {@code present()} shows it), present the generated frame into an additional swapchain image
	 * through the RT runtime, so the display order is generated-then-real. Runs only on the normal
	 * present path — the HDR/PQ present hooks cancel {@code blitFromTexture} at HEAD, so this TAIL is
	 * skipped there; HDR evaluates frame generation from its PQ backbuffer in the explicit HDR hook.
	 */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void caustica$prepareGeneratedFrame(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0
				|| !CausticaClientComposition.current().runtime().frameGenerationActive(Minecraft.getInstance().level != null)) {
			return;
		}
		long srcImage = textureView.texture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture t ? t.vkImage() : 0L;
		long srcView = caustica$vkImageView(textureView);
		if (srcImage == 0L) {
			return;
		}
		RtRuntime presentation = CausticaClientComposition.current().runtime();
		presentation.prepareGeneratedFrame(
				MinecraftVulkanBackend.wrap((VulkanCommandEncoder) commandEncoder), this.device.vkDevice(),
				this.swapchain, this.swapchainImages.toLongArray(), this.presentSemaphores,
				this.swapchainWidth, this.swapchainHeight,
				srcView, srcImage, false,
				CausticaClientComposition.current().uiOverlay().capturePresentation());
	}

	/**
	 * DLSS-FG on the HDR present path: same extra-present mechanism as {@link #caustica$prepareGeneratedFrame},
	 * but sourced from the presenter's PQ HDR backbuffer
	 * since HDR frames never reach that TAIL inject (HEAD cancels {@code blitFromTexture} above). No-op if FG
	 * isn't active or the HDR backbuffer isn't available (shouldn't happen right after a successful
	 * {@code presentHdr} call, but mirrors the defensive {@code srcImage == 0L} check in the SDR path).
	 */
	@Unique
	private void caustica$prepareGeneratedFrameHdr(GraphicsSubmission submission,
			UiPresentationResources ui) {
		if (this.currentImageIndex < 0
				|| !CausticaClientComposition.current().runtime().frameGenerationActive(Minecraft.getInstance().level != null)) {
			return;
		}
		RtRuntime presentation = CausticaClientComposition.current().runtime();
		long hdrView = presentation.hdrBackbufferView();
		long hdrImage = presentation.hdrBackbufferImage();
		if (hdrImage == 0L) {
			return;
		}
		presentation.prepareGeneratedFrame(submission, this.device.vkDevice(), this.swapchain,
				this.swapchainImages.toLongArray(),
				this.presentSemaphores, this.swapchainWidth, this.swapchainHeight,
				hdrView, hdrImage, true, ui);
	}

	// Present the FG-generated frame acquired/recorded at blitFromTexture TAIL — at present() HEAD, after
	// Minecraft.java's encoder.submit() has flushed (so our present semaphores are signaled) and before MC
	// presents the real frame, giving display order generated-then-real.
	@Inject(method = "present", at = @At("HEAD"))
	private void caustica$flushGeneratedPresent(CallbackInfo ci) {
		CausticaClientComposition.current().runtime().flushGeneratedPresent(this.swapchain, this.presentQueue);
	}
}
