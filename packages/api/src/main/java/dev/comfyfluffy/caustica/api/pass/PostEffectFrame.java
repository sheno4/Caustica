package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;

/**
 * Frame capabilities for a post-effect registration, adding what the post chain produces for it to read
 * and the target that enrols it.
 *
 * <p>Scene colour images are display-resolution; depth is trace-resolution. Each image carries its extent.
 */
public interface PostEffectFrame extends PassFrame {
    /**
     * The scene as it stands at this point in the chain: scene-linear ACEScg multiplied by preExposure(), not yet look
     * transformed, or tone mapped. For the first pass that acquires an output it is the reconstructed colour
     * after reconstruction; after that it is whatever the previous participating pass wrote.
     *
     * <p>Engine-produced and resolved fresh every frame — never cache the returned {@link GpuImage} across
     * frames, since a resize recreates it.
     */
    GpuImage sceneColor();

    /**
     * Join the chain: the image this pass writes its version of the scene into. Calling this explicit
     * acquisition method is what
     * enrols the pass in the ordered post-effect chain.
     *
     * <p>Always distinct from {@link #sceneColor()}, so write every pixel, including the ones the effect
     * does not change: the target holds the previous frame's chain contents, not a copy of the source.
     * That separation is what lets an effect gather from neighbouring pixels safely, which reading and
     * writing one image could not.
     *
     * <p>Two outputs rotate, so calling this twice in one {@code record} returns the same image; a pass
     * needing its own intermediates allocates them itself.
     */
    GpuImage acquireSceneColorOutput();

    /** Whether this frame's captured program resolves the selected spatial-medium implementation. */
    boolean spatialMediumActive();

    /** Physical first-hit Vulkan reverse depth at trace resolution; zero denotes environment. */
    GpuImage primaryDepth();

    /** Dominant virtual-endpoint Vulkan reverse depth at trace resolution; zero denotes environment. */
    GpuImage depth();

    /**
     * Column-major inverse of the frame projection and scene-to-view rotation. Multiplying
     * clip (x, y, depth, 1) and dividing by w gives camera-relative scene coordinates.
     * For trace-depth reconstruction, add traceJitter() to the pixel center before mapping to clip XY.
     */
    float[] cameraRelativeFromClip();

    /** Subpixel XY offset used by trace rays, in render-resolution pixels. */
    float[] traceJitter();

    /** Radiance multiplier already applied to sceneColor; apply it to added physical radiance as well. */
    float preExposure();

    /** Entry-scene acceleration structure, whose positions are relative to its current scene origin. */
    dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor entrySceneTlasDescriptor();

    /** Camera XYZ relative to the entry-scene acceleration structure's origin, in scene units. */
    float[] cameraTlasPosition();

    /**
     * Trace material-aware straight-line visibility through this frame's captured entry scene.
     * Rays and results contain width * height records, with X varying fastest. Each ray is 48 bytes:
     * float4(origin XYZ, minimum distance), float4(normalized direction XYZ, maximum distance), then
     * float4(initial absorption RGB per scene unit, initial IOR). Origins are TLAS-relative and distances
     * use scene units. A ray with maximum distance no greater than its minimum returns full visibility.
     * Each 16-byte result is float4(RGB transmittance, 1), without exposure scaling.
     *
     * <p>The caller retains both addressable buffers for this frame and records their input writes before
     * calling. This method orders earlier GPU writes before tracing and makes results ready for subsequent
     * compute reads. Buffers must not overlap. Width and height are positive.
     */
    void traceVisibility(dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress rays,
                         dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress results,
                         int width, int height);

    /**
     * Sample phase-weighted direct lighting from this frame's retained lights, including material-aware shadows.
     * Each 64-byte input contains float4(TLAS-relative position, minimum shadow distance),
     * float4(normalized camera-to-sample direction, Henyey-Greenstein anisotropy), and
     * float4(normalized screen UV, random-seed bits, active flag), and
     * float4(isotropic distant ambient radiance in ACEScg, unused). Ambient light is also shadowed.
     * A zero active flag returns zero radiance.
     * Each float4 result contains unexposed ACEScg incident radiance integrated against the phase function,
     * with alpha one. The caller applies scattering coefficients and integrates along the view ray.
     * Buffer ownership, extents and write/read ordering follow traceVisibility.
     */
    void sampleVolumeLighting(dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress samples,
                              dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress results,
                              int width, int height);

    /** Current scalar exposure, available only to ordinary post effects after exposure metering. */
    GpuImage exposureImage();
}
