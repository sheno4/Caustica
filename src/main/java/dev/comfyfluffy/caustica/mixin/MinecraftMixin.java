package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.rt.RtRuntime;
import dev.comfyfluffy.caustica.minecraft.MinecraftFrameAdapter;
import dev.comfyfluffy.caustica.minecraft.MinecraftUiOverlay;
import dev.comfyfluffy.caustica.minecraft.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Reflex per-frame sleep call must run at the very start of the frame, before input
 * sampling/simulation. {@link Minecraft#runTick} is Minecraft's per-loop-iteration entry point (called once
 * per {@code while (running)} iteration in {@link Minecraft#run()}, right after
 * {@code RenderSystem.pollEvents()}), so its HEAD is the earliest hookable point in this codebase for that
 * purpose — the alternative (hooking inside {@code run()}'s loop body directly) isn't a clean Mixin target
 * since it's inline in a loop, not a call to a named method.
 *
 * <p>SIMULATION_START/END bracket the tick/extract work that happens before rendering (from here through
 * just before {@code renderFrame} is called); RENDERSUBMIT/PRESENT markers are set from
 * {@code GameRendererMixin}/{@code VulkanGpuSurfaceMixin} respectively. No-ops entirely unless Reflex is
 * enabled and sleep mode has already been applied to the current swapchain.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void caustica$destroyUiOverlayBeforeRendererShutdown(CallbackInfo ci) {
		MinecraftUiOverlay.destroy();
		RtRuntime.INSTANCE.shutdown();
	}

	// Client-tick cadence, matching where the runtime tick has always run. runTick's HEAD would raise this
	// to frame rate and place session/resize work ahead of the Reflex sleep below.
	@Inject(method = "tick", at = @At("HEAD"))
	private void caustica$tickRuntime(CallbackInfo ci) {
		MinecraftFrameAdapter.INSTANCE.tickRuntime((Minecraft) (Object) this);
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void caustica$reflexSleepAndSimStart(boolean advanceGameTime, CallbackInfo ci) {
		VulkanLowLatency lowLatency = caustica$lowLatency();
		VulkanDevice device = caustica$reflexDevice();
		long swapchain = lowLatency == null ? 0L : lowLatency.appliedSwapchain();
		if (lowLatency == null || device == null || swapchain == 0L) {
			return;
		}
		lowLatency.sleep(device.vkDevice(), swapchain);
		lowLatency.marker(device.vkDevice(), swapchain, VulkanLowLatency.SIMULATION_START,
				lowLatency.currentSimulationId());
	}

	@Inject(method = "runTick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void caustica$reflexSimEnd(boolean advanceGameTime, CallbackInfo ci) {
		VulkanLowLatency lowLatency = caustica$lowLatency();
		VulkanDevice device = caustica$reflexDevice();
		long swapchain = lowLatency == null ? 0L : lowLatency.appliedSwapchain();
		if (lowLatency == null || device == null || swapchain == 0L) {
			return;
		}
		lowLatency.marker(device.vkDevice(), swapchain, VulkanLowLatency.SIMULATION_END,
				lowLatency.currentSimulationId());
	}

	private static VulkanLowLatency caustica$lowLatency() {
		MinecraftVulkanBackend backend = MinecraftVulkanBackend.current();
		return backend != null && backend.lowLatency().active() ? backend.lowLatency() : null;
	}

	private static VulkanDevice caustica$reflexDevice() {
		if (caustica$lowLatency() == null) {
			return null;
		}
		return ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device
				? device : null;
	}
}
