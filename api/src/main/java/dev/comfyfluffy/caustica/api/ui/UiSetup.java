package dev.comfyfluffy.caustica.api.ui;

import dev.comfyfluffy.caustica.api.pass.PassSetup;

/**
 * Fixed format needed to create a UI pipeline for one render session.
 *
 * @param common common pass services
 * @param layerFormat UI-layer VkFormat
 */
public record UiSetup(PassSetup common, int layerFormat) {
    public UiSetup {
        if (common == null) throw new NullPointerException("common");
    }
}
