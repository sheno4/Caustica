package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import java.util.Set;
import org.lwjgl.vulkan.KHRGetSurfaceCapabilities2;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Requires the modern surface-capability query extension and enables extended swapchain color spaces when
 * available. Together they expose the format/color-space pairs used by {@code VulkanGpuSurfaceMixin} to
 * select an HDR10/PQ swapchain. The color-space extension only adds enum values; swapchain creation still
 * explicitly chooses the active pair.
 *
 * <p>The modern query extension is part of the renderer's required instance profile, so startup fails with
 * a direct error when it is unavailable. The optional color-space extension is enabled only when supported.
 */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
	private static final String SWAPCHAIN_COLORSPACE = "VK_EXT_swapchain_colorspace";
	private static final String SURFACE_CAPABILITIES_2 =
			KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME;

	@Shadow
	@Final
	private Set<String> enabledExtensions;

	@ModifyArg(
			method = "<init>",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"),
			index = 0)
	private int caustica$requireVulkan14(int hostVersion) {
		return CausticaClientComposition.current().deviceBringup().requestInstanceApiVersion();
	}

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Set;size()I"))
	private void caustica$addSurfaceExtensions(int debugVerbosity, boolean wantsDebugLabels, boolean validation,
			CallbackInfo ci, @Local(ordinal = 0) Set<String> availableExtensions) {
		if (caustica$enableRequiredSurfaceQuery(availableExtensions, this.enabledExtensions)) {
			CausticaMod.LOGGER.info("Enabling instance extension {} for modern surface queries",
					SURFACE_CAPABILITIES_2);
		}
		if (availableExtensions.contains(SWAPCHAIN_COLORSPACE)) {
			if (this.enabledExtensions.add(SWAPCHAIN_COLORSPACE)) {
				CausticaMod.LOGGER.info("Enabling instance extension {} for HDR swapchain color spaces", SWAPCHAIN_COLORSPACE);
			}
		} else {
			CausticaMod.LOGGER.warn("Instance extension {} unavailable; HDR color spaces will not be queryable on this platform", SWAPCHAIN_COLORSPACE);
		}
	}

	private static boolean caustica$enableRequiredSurfaceQuery(
			Set<String> availableExtensions, Set<String> enabledExtensions) {
		if (!availableExtensions.contains(SURFACE_CAPABILITIES_2)) {
			throw new IllegalStateException("Required Vulkan instance extension is unavailable: "
					+ SURFACE_CAPABILITIES_2);
		}
		return enabledExtensions.add(SURFACE_CAPABILITIES_2);
	}

	@ModifyArg(
			method = "<init>",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"),
			index = 0)
	private VkInstanceCreateInfo caustica$logInstanceLayers(VkInstanceCreateInfo createInfo) {
		VulkanDiagnostics.logInstanceLayers(createInfo);
		return createInfo;
	}
}
