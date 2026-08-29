package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RendererShaderNeutralityTest {
    @Test
    void directLightBeerLambertUsesOnlyTheCurrentMediumSegmentPerChannel() {
        double[] extinction = {Math.log(2.0), 0.0, Math.log(4.0)};

        assertEquals(0.125, beerLambert(extinction[0], 3.0), 1.0e-12,
                "a finite light inside the medium uses its full distance");
        assertEquals(0.5, beerLambert(extinction[0], 1.0), 1.0e-12,
                "a light beyond the boundary uses only the boundary distance");
        assertEquals(1.0 / 16.0, beerLambert(extinction[2], 2.0), 1.0e-12);
        assertEquals(0.0, unboundedBeerLambert(extinction[0]), 0.0);
        assertEquals(1.0, unboundedBeerLambert(extinction[1]), 0.0,
                "an unbounded path preserves a channel with zero extinction");
        assertEquals(0.0, unboundedBeerLambert(extinction[2]), 0.0);
    }

    private static double beerLambert(double extinction, double distance) {
        return Math.exp(-extinction * distance);
    }

    private static double unboundedBeerLambert(double extinction) {
        return extinction > 0.0 ? 0.0 : 1.0;
    }
}
