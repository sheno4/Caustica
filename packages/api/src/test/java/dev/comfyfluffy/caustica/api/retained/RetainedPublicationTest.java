package dev.comfyfluffy.caustica.api.retained;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RetainedPublicationTest {
    @Test
    void alreadyVisibleRunsCallbacksImmediately() {
        RetainedPublication publication = RetainedPublication.alreadyVisible();
        AtomicInteger callbacks = new AtomicInteger();

        publication.whenVisible(callbacks::incrementAndGet);

        assertTrue(publication.isVisible());
        assertEquals(1, callbacks.get());
    }
}
