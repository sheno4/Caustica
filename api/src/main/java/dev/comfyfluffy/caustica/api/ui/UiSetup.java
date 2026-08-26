package dev.comfyfluffy.caustica.api.ui;

import dev.comfyfluffy.caustica.api.pass.PassSetup;

/**
 * {@link PassSetup} for a {@link UiPass}, adding the one fact a UI pass needs that the others do not: the
 * format of the layer it draws into, which its pipelines must declare as their colour attachment.
 */
public interface UiSetup extends PassSetup {
    /** The sRGB VkFormat the UI layer is created with. */
    int layerFormat();
}
