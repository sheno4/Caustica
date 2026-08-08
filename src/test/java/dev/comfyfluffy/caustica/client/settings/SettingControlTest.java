package dev.comfyfluffy.caustica.client.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The slider mapping, which every numeric row shares. Worth pinning without a GUI because the awkward cases
 * — a narrowed span, an integer step, a stored value outside the span — are exactly the ones a widget test
 * would not reach.
 */
final class SettingControlTest {
    /** Minimal stub: only the four numbers {@code toSlider}/{@code fromSlider} actually read. */
    private record Range(double sliderMinimum, double sliderMaximum, double step)
            implements SettingControl.RangeControl {
        @Override
        public String id() {
            return "stub";
        }

        @Override
        public net.minecraft.network.chat.Component label() {
            return net.minecraft.network.chat.Component.empty();
        }

        @Override
        public net.minecraft.network.chat.Component tooltip() {
            return net.minecraft.network.chat.Component.empty();
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public double get() {
            return sliderMinimum;
        }

        @Override
        public void set(double value) {
        }

        @Override
        public double defaultValue() {
            return sliderMinimum;
        }

        @Override
        public net.minecraft.network.chat.Component format(double value) {
            return net.minecraft.network.chat.Component.empty();
        }
    }

    @Test
    void aContinuousRangeMapsBothWays() {
        Range range = new Range(0.0, 4.0, 0.0);

        assertEquals(0.0, range.toSlider(0.0));
        assertEquals(0.5, range.toSlider(2.0));
        assertEquals(1.0, range.toSlider(4.0));
        assertEquals(2.0, range.fromSlider(0.5));
        assertEquals(4.0, range.fromSlider(1.0));
    }

    @Test
    void anOffsetMinimumIsNotAssumedToBeZero() {
        Range range = new Range(0.25, 4.25, 0.0);

        assertEquals(0.0, range.toSlider(0.25));
        assertEquals(0.5, range.toSlider(2.25));
        assertEquals(2.25, range.fromSlider(0.5));
    }

    @Test
    void aNegativeMinimumMapsWithoutSignTrouble() {
        Range range = new Range(-15.0, 15.0, 0.0);

        assertEquals(0.5, range.toSlider(0.0));
        assertEquals(0.0, range.fromSlider(0.5));
        assertEquals(-15.0, range.fromSlider(0.0));
    }

    /** A value loaded from a hand-edited config can sit outside the span; the knob pins, it does not wrap. */
    @Test
    void aValueOutsideTheSpanPinsAtTheEdge() {
        Range range = new Range(0.0, 16.0, 0.0);

        assertEquals(1.0, range.toSlider(65504.0));
        assertEquals(0.0, range.toSlider(-100.0));
    }

    @Test
    void aStepQuantisesToWholeIncrementsFromTheMinimum() {
        Range range = new Range(1.0, 8.0, 1.0);

        assertEquals(1.0, range.fromSlider(0.0));
        assertEquals(8.0, range.fromSlider(1.0));
        assertEquals(5.0, range.fromSlider(4.0 / 7.0));
        // Anywhere inside a step's half-width lands on that step, so a drag cannot produce 5.3 levels.
        assertEquals(5.0, range.fromSlider(4.05 / 7.0));
        assertEquals(5.0, range.fromSlider(3.95 / 7.0));
    }

    @Test
    void aSteppedRangeRoundTripsEveryLegalValue() {
        Range range = new Range(1.0, 8.0, 1.0);

        for (int value = 1; value <= 8; value++) {
            assertEquals(value, range.fromSlider(range.toSlider(value)), "step " + value);
        }
    }

    @Test
    void aSteppedRangeWithANonUnitStepStillLandsOnTheGrid() {
        Range range = new Range(0.0, 1.0, 0.25);

        assertEquals(0.25, range.fromSlider(0.3));
        assertEquals(0.5, range.fromSlider(0.45));
        assertEquals(1.0, range.fromSlider(1.0));
    }
}
