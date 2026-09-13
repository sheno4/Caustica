package dev.comfyfluffy.caustica.renderer.presentation.fog;

/**
 * Medium heights are in scene units. Lighting uses scene-linear ACEScg before pre-exposure.
 * {@code lightRadiance} carries integrated distant illuminance in lux, multiplied by the scattering
 * phase function in inverse steradians; {@code ambientRadiance} carries ambient luminance in cd/m².
 * {@code lightDirection} is a unit vector pointing toward the distant light.
 */
public record FogFrame(FogField field, float timeDensity, float layerHeight, float heightFalloff,
                       float windTime, float[] lightDirection, float[] lightRadiance, float[] ambientRadiance) {
    public FogFrame {
        lightDirection = lightDirection.clone();
        lightRadiance = lightRadiance.clone();
        ambientRadiance = ambientRadiance.clone();
    }
    @Override public float[] lightDirection() { return lightDirection.clone(); }
    @Override public float[] lightRadiance() { return lightRadiance.clone(); }
    @Override public float[] ambientRadiance() { return ambientRadiance.clone(); }
}
