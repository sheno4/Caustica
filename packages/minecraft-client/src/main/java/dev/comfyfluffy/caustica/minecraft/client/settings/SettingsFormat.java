package dev.comfyfluffy.caustica.minecraft.client.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Number formatting shared by every row, so one value never reads two ways in one screen. */
final class SettingsFormat {
    private SettingsFormat() {
    }

    /**
     * Two decimals, trailing zeros and a trailing point trimmed: 0.02 stays "0.02" while 30.0 reads "30".
     * A slider label changes every frame during a drag, so a fixed width would jitter and a raw
     * {@code Double.toString} would spell out float noise.
     */
    static String decimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
