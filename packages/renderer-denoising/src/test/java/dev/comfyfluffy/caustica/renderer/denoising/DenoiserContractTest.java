package dev.comfyfluffy.caustica.renderer.denoising;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DenoiserContractTest {
    private static final DenoiserExtent FULL_HD = new DenoiserExtent(1920, 1080);

    @Test
    void commonSettingsOwnTheirMatrixSnapshots() {
        float[] matrix = identity();
        DenoiserCommonSettings settings = settings(matrix);
        matrix[0] = 9.0f;
        float[] firstRead = settings.worldToView();
        firstRead[0] = 7.0f;
        assertNotSame(firstRead, settings.worldToView());
        assertEquals(1.0f, settings.worldToView()[0]);
    }

    @Test
    void rejectsInvalidCommonSettings() {
        assertThrows(IllegalArgumentException.class, () -> settings(new float[15]));
        assertThrows(IllegalArgumentException.class, () -> common(0.0f, 16.0f));
        assertThrows(IllegalArgumentException.class, () -> common(1.0f, -0.01f));
    }

    @Test
    void extentAndImageRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new DenoiserExtent(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DenoiserExtent(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new DenoiserImage(0L, 1, 1, FULL_HD));
        assertThrows(IllegalArgumentException.class, () -> new DenoiserImage(1L, 0, 1, FULL_HD));
        assertThrows(NullPointerException.class, () -> new DenoiserImage(1L, 1, 1, null));
    }

    @Test
    void descriptorRequiresAnExtentAndSignalEncoding() {
        assertThrows(NullPointerException.class, () -> new DenoiserBackendDescriptor(null,
                DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE));
        assertThrows(NullPointerException.class, () -> new DenoiserBackendDescriptor(FULL_HD, null));
        DenoiserBackendDescriptor descriptor = descriptor(FULL_HD);
        assertEquals(FULL_HD, descriptor.extent());
        assertEquals(DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE, descriptor.signalEncoding());
    }

    @Test
    void allInputImagesMustHaveTheDiffuseSignalExtent() {
        DenoiserImage full = image(1, FULL_HD);
        DenoiserImage half = image(2, new DenoiserExtent(960, 540));

        assertThrows(IllegalArgumentException.class, () -> inputs(full, half, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> inputs(full, full, Optional.of(half), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> inputs(full, full, Optional.empty(), Optional.of(half)));
    }

    @Test
    void inputsExposeTheirValidatedExtent() {
        DenoiserImage full = image(1, FULL_HD);
        DenoiserInputs inputs = inputs(full, full, Optional.of(full), Optional.of(full));
        assertEquals(FULL_HD, inputs.extent());
    }

    @Test
    void frameRejectsNullCommandBufferAndExposesInputExtent() {
        DenoiserImage image = image(1, FULL_HD);
        DenoiserInputs inputs = inputs(image, image, Optional.empty(), Optional.empty());
        DenoiserCommonSettings common = settings(identity());
        assertThrows(IllegalArgumentException.class, () -> new DenoiserFrame(0, common, inputs));
        assertEquals(FULL_HD, new DenoiserFrame(1, common, inputs).extent());
    }

    private static DenoiserBackendDescriptor descriptor(DenoiserExtent extent) {
        return new DenoiserBackendDescriptor(extent,
                DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE);
    }

    private static DenoiserInputs inputs(
            DenoiserImage diffuse,
            DenoiserImage specular,
            Optional<DenoiserImage> disocclusion,
            Optional<DenoiserImage> validation) {
        return new DenoiserInputs(diffuse, specular, diffuse, diffuse, diffuse, diffuse, diffuse,
                disocclusion, validation);
    }

    private static DenoiserImage image(long handle, DenoiserExtent extent) {
        return new DenoiserImage(handle, 97, 1, extent);
    }

    private static DenoiserCommonSettings settings(float[] matrix) {
        return new DenoiserCommonSettings(matrix, identity(), identity(), identity(),
                0, 0, 0, 0, 1, 1, 0, 1000, 0.01f, 0.02f, 16.0f, 0,
                false, DenoiserReset.CONTINUE);
    }

    private static DenoiserCommonSettings common(float range, float frameTime) {
        return new DenoiserCommonSettings(identity(), identity(), identity(), identity(),
                0, 0, 0, 0, 1, 1, 0, range, 0.01f, 0.02f, frameTime, 0,
                false, DenoiserReset.CONTINUE);
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }
}
