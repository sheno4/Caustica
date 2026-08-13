package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void thinWallSubsurfaceSplitConservesTheAuthoredScatteringAlbedo() {
        double[] weights = {0.0, 0.2, 0.5, 1.0};
        double[] colors = {0.0, 0.15, 0.8, 1.0};
        double[] anisotropies = {-1.0, -0.6, 0.0, 0.7, 1.0};
        for (double weight : weights) {
            for (double color : colors) {
                for (double anisotropy : anisotropies) {
                    double reflection = weight * color * 0.5 * (1.0 - anisotropy);
                    double transmission = weight * color * 0.5 * (1.0 + anisotropy);
                    assertEquals(weight * color, reflection + transmission, 1.0e-12);
                    assertTrue(reflection >= 0.0 && transmission >= 0.0);
                }
            }
        }
    }

    @Test
    void thinDielectricInterreflectionLadderNeverCreatesEnergy() {
        double[] fresnels = {0.0, 0.02, 0.04, 0.2, 0.7, 0.99, 1.0};
        double[] transmittances = {0.0, 0.1, 0.5, 0.9, 1.0};
        for (double fresnel : fresnels) {
            for (double transmittance : transmittances) {
                double denominator = Math.max(1.0 - fresnel * fresnel
                        * transmittance * transmittance, 1.0e-6);
                double oneMinusF = 1.0 - fresnel;
                double reflection = fresnel + oneMinusF * oneMinusF * fresnel
                        * transmittance * transmittance / denominator;
                double transmission = oneMinusF * oneMinusF * transmittance / denominator;
                assertTrue(reflection >= 0.0 && transmission >= 0.0);
                assertTrue(reflection + transmission <= 1.0 + 1.0e-10,
                        () -> "thin-sheet energy " + (reflection + transmission)
                                + " for F=" + fresnel + ", A=" + transmittance);
                if (transmittance == 1.0) {
                    assertEquals(1.0, reflection + transmission, 1.0e-9);
                }
            }
        }
    }

    @Test
    void mixedThinWallClosurePassesTwoHemisphereFurnaceSweep() {
        ThinCase[] cases = {
                new ThinCase(0.04, 0.0, 1.0, 0.0, 0.8, 0.0),
                new ThinCase(0.04, 0.0, 1.0, 1.0, 1.0, 0.0),
                new ThinCase(0.04, 0.0, 1.0, 1.0, 0.7, -0.7),
                new ThinCase(0.04, 0.0, 1.0, 1.0, 0.7, 0.7),
                new ThinCase(0.04, 1.0, 1.0, 0.0, 0.8, 0.0),
                new ThinCase(0.04, 1.0, 0.25, 0.0, 0.8, 0.0),
                new ThinCase(0.08, 0.5, 0.8, 0.5, 0.6, 0.4),
                new ThinCase(0.2, 0.35, 0.45, 0.8, 0.9, -0.3)
        };
        double[] viewCosines = {0.1, 0.5, 1.0};
        double[] alphas = {0.04, 0.16, 0.64};

        for (ThinCase material : cases) {
            for (double viewCosine : viewCosines) {
                for (double alpha : alphas) {
                    double response = thinWallFurnaceResponse(viewCosine, alpha, material);
                    assertTrue(Double.isFinite(response) && response >= 0.0,
                            () -> "non-finite thin-wall response " + response + " for " + material);
                    assertTrue(response <= 1.01,
                            () -> "thin-wall furnace response " + response + " for NoV="
                                    + viewCosine + ", alpha=" + alpha + ", " + material);
                }
            }
        }

        // The midpoint sweep deliberately avoids the near-delta alpha floor: resolving that peak would
        // require orders of magnitude more angular cells without testing a different energy identity.
        double losslessSheet = thinWallFurnaceResponse(1.0, 0.16,
                new ThinCase(0.04, 1.0, 1.0, 0.0, 1.0, 0.0));
        double losslessScatterer = thinWallFurnaceResponse(1.0, 0.16,
                new ThinCase(0.04, 0.0, 1.0, 1.0, 1.0, 0.0));
        assertTrue(losslessSheet > 0.9, "smooth lossless sheet should retain energy: " + losslessSheet);
        assertTrue(losslessScatterer > 0.85,
                "smooth white scattering sheet should retain energy: " + losslessScatterer);
    }

    @Test
    void everyNonzeroThinWallLobeHasSelectionSupport() {
        ThinCase[] cases = {
                new ThinCase(0.04, 0.0, 1.0, 0.0, 0.8, 0.0),
                new ThinCase(0.04, 0.0, 1.0, 1.0, 1.0, 0.0),
                new ThinCase(0.04, 1.0, 1.0, 0.0, 0.8, 0.0),
                new ThinCase(0.08, 0.5, 0.8, 0.5, 0.6, 0.4)
        };
        for (ThinCase material : cases) {
            double[] probabilities = lobeProbabilities(material, 0.5);
            assertEquals(1.0, probabilities[0] + probabilities[1]
                    + probabilities[2] + probabilities[3], 1.0e-12);
            double[] values = lobeImportances(material, 0.5);
            for (int lobe = 0; lobe < values.length; lobe++) {
                if (values[lobe] > 0.0) {
                    int index = lobe;
                    assertTrue(probabilities[lobe] > 0.0,
                            () -> "lobe " + index + " has value but no sampling support for " + material);
                }
            }
        }
    }

    private static double thinWallFurnaceResponse(double viewCosine, double alpha,
                                                   ThinCase material) {
        int cosineSteps = 96;
        int azimuthSteps = 192;
        double cosineWidth = 1.0 / cosineSteps;
        double azimuthWidth = 2.0 * PI / azimuthSteps;
        double viewSine = Math.sqrt(1.0 - viewCosine * viewCosine);
        double opaqueWeight = 1.0 - material.transmissionWeight;
        double diffuseReflection = opaqueWeight * ((1.0 - material.subsurfaceWeight)
                + material.subsurfaceWeight * material.subsurfaceColor
                * 0.5 * (1.0 - material.anisotropy));
        double diffuseTransmission = opaqueWeight * material.subsurfaceWeight
                * material.subsurfaceColor * 0.5 * (1.0 + material.anisotropy);
        double sum = 0.0;

        for (int hemisphere = 0; hemisphere < 2; hemisphere++) {
            boolean transmitted = hemisphere == 1;
            for (int cosineIndex = 0; cosineIndex < cosineSteps; cosineIndex++) {
                double lightCosine = (cosineIndex + 0.5) * cosineWidth;
                double lightSine = Math.sqrt(1.0 - lightCosine * lightCosine);
                for (int azimuthIndex = 0; azimuthIndex < azimuthSteps; azimuthIndex++) {
                    double azimuth = (azimuthIndex + 0.5) * azimuthWidth;
                    // The far-side direction is mirrored into the view hemisphere before forming the
                    // thin-transmission half vector, so both lobes share this upper-hemisphere geometry.
                    double halfX = viewSine + lightSine * Math.cos(azimuth);
                    double halfY = lightSine * Math.sin(azimuth);
                    double halfZ = viewCosine + lightCosine;
                    double inverseHalfLength = 1.0 / Math.sqrt(
                            halfX * halfX + halfY * halfY + halfZ * halfZ);
                    double normalHalf = halfZ * inverseHalfLength;
                    double viewHalf = (viewSine * halfX + viewCosine * halfZ)
                            * inverseHalfLength;
                    double distribution = ggxD(normalHalf, alpha);
                    double geometry = ggxG1(viewCosine, alpha) * ggxG1(lightCosine, alpha);
                    double fresnel = fresnelSchlick(viewHalf, material.f0);
                    double sheetReflection = thinSheetReflection(
                            fresnel, material.transmissionColor);
                    double sheetTransmission = thinSheetTransmission(
                            fresnel, material.transmissionColor);
                    double glossy = distribution * geometry * (transmitted
                            ? sheetTransmission * material.transmissionWeight
                            : lerp(fresnel, sheetReflection, material.transmissionWeight))
                            / (4.0 * viewCosine * lightCosine);
                    double diffuse = (transmitted ? diffuseTransmission : diffuseReflection)
                            * (1.0 - fresnelSchlick(viewCosine, material.f0))
                            * (1.0 - fresnelSchlick(lightCosine, material.f0)) / PI;
                    sum += (diffuse + glossy) * lightCosine * cosineWidth * azimuthWidth;
                }
            }
        }
        return sum;
    }

    private static double[] lobeProbabilities(ThinCase material, double outgoingCosine) {
        double[] importance = lobeImportances(material, outgoingCosine);
        double total = importance[0] + importance[1] + importance[2] + importance[3];
        return new double[]{importance[0] / total, importance[1] / total,
                importance[2] / total, importance[3] / total};
    }

    private static double[] lobeImportances(ThinCase material, double outgoingCosine) {
        double fresnel = fresnelSchlick(outgoingCosine, material.f0);
        double sheetReflection = thinSheetReflection(fresnel, material.transmissionColor);
        double sheetTransmission = thinSheetTransmission(fresnel, material.transmissionColor);
        double opaqueWeight = 1.0 - material.transmissionWeight;
        double diffuseReflection = opaqueWeight * ((1.0 - material.subsurfaceWeight)
                + material.subsurfaceWeight * material.subsurfaceColor
                * 0.5 * (1.0 - material.anisotropy));
        double diffuseTransmission = opaqueWeight * material.subsurfaceWeight
                * material.subsurfaceColor * 0.5 * (1.0 + material.anisotropy);
        return new double[]{
                lerp(fresnel, sheetReflection, material.transmissionWeight),
                diffuseReflection,
                diffuseTransmission,
                sheetTransmission * material.transmissionWeight
        };
    }

    private static double thinSheetReflection(double fresnel, double transmittance) {
        double denominator = Math.max(1.0 - fresnel * fresnel
                * transmittance * transmittance, 1.0e-6);
        double oneMinusF = 1.0 - fresnel;
        return fresnel + oneMinusF * oneMinusF * fresnel
                * transmittance * transmittance / denominator;
    }

    private static double thinSheetTransmission(double fresnel, double transmittance) {
        double denominator = Math.max(1.0 - fresnel * fresnel
                * transmittance * transmittance, 1.0e-6);
        double oneMinusF = 1.0 - fresnel;
        return oneMinusF * oneMinusF * transmittance / denominator;
    }

    private static double lerp(double from, double to, double weight) {
        return from + weight * (to - from);
    }

    private record ThinCase(double f0, double transmissionWeight, double transmissionColor,
                            double subsurfaceWeight, double subsurfaceColor, double anisotropy) {
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
        return alphaSquared / (PI * denominator * denominator);
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
