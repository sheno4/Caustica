package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU reference for the glossy-over-diffuse layering implemented by {@code surface_bsdf.slang}. */
final class RtOpenPbrEnergyTest {
    private static final double PI = Math.PI;

    @Test
    void currentSubsetDoesNotReturnMoreThanAWhiteFurnaceSupplies() {
        double[] viewCosines = {0.05, 0.1, 0.25, 0.5, 0.75, 1.0};
        double[] alphas = {0.0025, 0.01, 0.04, 0.16, 0.36, 0.64, 1.0};
        double[] f0s = {0.0, 0.02, 0.04, 0.08, 0.2, 0.5, 0.8, 1.0};

        for (double viewCosine : viewCosines) {
            for (double alpha : alphas) {
                for (double f0 : f0s) {
                    double response = furnaceResponse(viewCosine, alpha, f0, true);
                    assertTrue(response <= 1.002,
                            () -> "white-furnace response " + response + " for NoV=" + viewCosine
                                    + ", alpha=" + alpha + ", F0=" + f0);
                }
            }
        }
    }

    @Test
    void furnaceCheckDetectsAnUnattenuatedDiffuseAndGlossySum() {
        double response = furnaceResponse(1.0, 0.16, 0.5, false);
        assertTrue(response > 1.4, "control must expose the energy gain, got " + response);
    }

    private static double furnaceResponse(double viewCosine, double alpha, double f0,
                                           boolean layerDiffuse) {
        int cosineSteps = 128;
        int azimuthSteps = 256;
        double cosineWidth = 1.0 / cosineSteps;
        double azimuthWidth = 2.0 * PI / azimuthSteps;
        double viewSine = Math.sqrt(1.0 - viewCosine * viewCosine);
        double sum = 0.0;

        for (int cosineIndex = 0; cosineIndex < cosineSteps; cosineIndex++) {
            double lightCosine = (cosineIndex + 0.5) * cosineWidth;
            double lightSine = Math.sqrt(1.0 - lightCosine * lightCosine);
            for (int azimuthIndex = 0; azimuthIndex < azimuthSteps; azimuthIndex++) {
                double azimuth = (azimuthIndex + 0.5) * azimuthWidth;
                double halfX = viewSine + lightSine * Math.cos(azimuth);
                double halfY = lightSine * Math.sin(azimuth);
                double halfZ = viewCosine + lightCosine;
                double inverseHalfLength = 1.0 / Math.sqrt(
                        halfX * halfX + halfY * halfY + halfZ * halfZ);
                double normalHalf = halfZ * inverseHalfLength;
                double viewHalf = (viewSine * halfX + viewCosine * halfZ) * inverseHalfLength;

                double distribution = ggxD(normalHalf, alpha);
                double geometry = ggxG1(viewCosine, alpha) * ggxG1(lightCosine, alpha);
                double specular = distribution * geometry * fresnelSchlick(viewHalf, f0)
                        / (4.0 * viewCosine * lightCosine);
                double diffuseWeight = layerDiffuse
                        ? (1.0 - fresnelSchlick(viewCosine, f0))
                                * (1.0 - fresnelSchlick(lightCosine, f0))
                        : 1.0;
                double bsdf = diffuseWeight / PI + specular;
                sum += bsdf * lightCosine * cosineWidth * azimuthWidth;
            }
        }
        return sum;
    }

    private static double fresnelSchlick(double cosine, double f0) {
        double m = clamp(1.0 - cosine, 0.0, 1.0);
        double grazing = clamp(50.0 * f0, 0.0, 1.0);
        return f0 + (grazing - f0) * m * m * m * m * m;
    }

    private static double ggxD(double normalHalf, double alpha) {
        double alphaSquared = alpha * alpha;
        double denominator = normalHalf * normalHalf * (alphaSquared - 1.0) + 1.0;
        return alphaSquared / (PI * denominator * denominator + 1.0e-7);
    }

    private static double ggxG1(double normalDirection, double alpha) {
        double alphaSquared = alpha * alpha;
        return 2.0 * normalDirection / (normalDirection
                + Math.sqrt(alphaSquared + (1.0 - alphaSquared)
                        * normalDirection * normalDirection) + 1.0e-7);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
