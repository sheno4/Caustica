package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;

import java.util.Objects;

/** Images produced by tracing and consumed by reconstruction or presentation. */
public record TraceImages(
        GpuImage traceColor,
        GpuImage stablePlaneMetadata,
        GpuImage normalRoughness,
        GpuImage diffuseAlbedo,
        GpuImage linearDepth,
        GpuImage motion,
        GpuImage specularAlbedo,
        GpuImage specularMotion,
        GpuImage reconstructedColor) {
    public TraceImages {
        Objects.requireNonNull(traceColor, "traceColor");
        Objects.requireNonNull(stablePlaneMetadata, "stablePlaneMetadata");
        Objects.requireNonNull(normalRoughness, "normalRoughness");
        Objects.requireNonNull(diffuseAlbedo, "diffuseAlbedo");
        Objects.requireNonNull(linearDepth, "linearDepth");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(specularAlbedo, "specularAlbedo");
        Objects.requireNonNull(specularMotion, "specularMotion");
        Objects.requireNonNull(reconstructedColor, "reconstructedColor");
    }
}
