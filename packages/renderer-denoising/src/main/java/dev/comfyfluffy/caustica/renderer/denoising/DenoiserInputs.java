package dev.comfyfluffy.caustica.renderer.denoising;

import java.util.Objects;
import java.util.Optional;

/** Full-resolution diffuse/specular signals, temporal guides, and denoised outputs. */
public record DenoiserInputs(
        DenoiserImage diffuseRadianceHitDistance,
        DenoiserImage specularRadianceHitDistance,
        DenoiserImage normalRoughness,
        DenoiserImage viewZ,
        DenoiserImage motion,
        DenoiserImage denoisedDiffuseRadianceHitDistance,
        DenoiserImage denoisedSpecularRadianceHitDistance,
        Optional<DenoiserImage> disocclusionThresholdMix,
        Optional<DenoiserImage> validationOutput) {
    public DenoiserInputs {
        Objects.requireNonNull(diffuseRadianceHitDistance, "diffuseRadianceHitDistance");
        Objects.requireNonNull(specularRadianceHitDistance, "specularRadianceHitDistance");
        Objects.requireNonNull(normalRoughness, "normalRoughness");
        Objects.requireNonNull(viewZ, "viewZ");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(denoisedDiffuseRadianceHitDistance, "denoisedDiffuseRadianceHitDistance");
        Objects.requireNonNull(denoisedSpecularRadianceHitDistance, "denoisedSpecularRadianceHitDistance");
        disocclusionThresholdMix = Objects.requireNonNull(disocclusionThresholdMix, "disocclusionThresholdMix");
        validationOutput = Objects.requireNonNull(validationOutput, "validationOutput");

        DenoiserExtent extent = diffuseRadianceHitDistance.extent();
        requireExtent(specularRadianceHitDistance, extent, "specularRadianceHitDistance");
        requireExtent(normalRoughness, extent, "normalRoughness");
        requireExtent(viewZ, extent, "viewZ");
        requireExtent(motion, extent, "motion");
        requireExtent(denoisedDiffuseRadianceHitDistance, extent, "denoisedDiffuseRadianceHitDistance");
        requireExtent(denoisedSpecularRadianceHitDistance, extent, "denoisedSpecularRadianceHitDistance");
        disocclusionThresholdMix.ifPresent(image -> requireExtent(image, extent, "disocclusionThresholdMix"));
        validationOutput.ifPresent(image -> requireExtent(image, extent, "validationOutput"));
    }

    public DenoiserExtent extent() {
        return diffuseRadianceHitDistance.extent();
    }

    private static void requireExtent(DenoiserImage image, DenoiserExtent expected, String name) {
        if (!expected.equals(image.extent())) {
            throw new IllegalArgumentException(name + " extent must match the diffuse signal extent");
        }
    }
}
