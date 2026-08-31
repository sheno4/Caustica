package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PresentationResourcesTest {
    @Test
    void startsUnsizedWithItsOwnExposureController() {
        RtExposure.Settings settings = new RtExposure.Settings("manual", 0.0f, 0.18f,
                1.0f, 1.0f, 0.1f, 0.9f, 1, 1.0f, 0.0f,
                1.0f, 1.0f, true, false, 2.2f);
        PresentationResources resources = new PresentationResources(RtLookPackage.loadDefault(), settings);
        assertFalse(resources.matches(1920, 1080));
        assertSame(resources.exposure(), resources.exposure());
    }

}
