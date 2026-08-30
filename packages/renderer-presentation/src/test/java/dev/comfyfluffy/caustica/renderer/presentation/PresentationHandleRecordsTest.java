package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PresentationHandleRecordsTest {
    @Test
    void swapchainCopiesItsImageTable() {
        ArrayList<PresentationSwapchain.Image> images = new ArrayList<>(List.of(
                new PresentationSwapchain.Image(11L, 21L),
                new PresentationSwapchain.Image(12L, 22L)));
        PresentationSwapchain swapchain = new PresentationSwapchain(
                null, 2L, 3, 4, 5, images);

        images.clear();

        assertEquals(List.of(
                new PresentationSwapchain.Image(11L, 21L),
                new PresentationSwapchain.Image(12L, 22L)), swapchain.images());
        assertThrows(UnsupportedOperationException.class,
                () -> swapchain.images().add(new PresentationSwapchain.Image(13L, 23L)));
    }

    @Test
    void acquiredTargetKeepsImageExtentAndSynchronizationTogether() {
        AcquiredSwapchainTarget target = new AcquiredSwapchainTarget(
                1L, 2L, 3, 4, 5, 6, 7L, 8L);

        assertEquals(1L, target.swapchain());
        assertEquals(2L, target.image());
        assertEquals(3, target.imageIndex());
        assertEquals(4, target.format());
        assertEquals(5, target.width());
        assertEquals(6, target.height());
        assertEquals(7L, target.acquireSemaphore());
        assertEquals(8L, target.presentSemaphore());
    }
}
