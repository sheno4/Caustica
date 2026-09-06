package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FrameOwnershipTest {
    @Test
    void abandoningReplacementFrameCannotReleaseAnEarlierSubmittedReadersResource() {
        var destroyed = new AtomicInteger();
        var producer = SharedResource.owned(new Object(), ignored -> destroyed.incrementAndGet());
        var submitted = new GraphicsUse(null, 1L);
        var firstClaim = producer.retain();
        submitted.whenComplete(firstClaim::close);
        submitted.commandsAccepted();
        var pending = new ArrayList<Runnable>();
        submitted.resolveSubmission(() -> { }, pending::add);

        var abandoned = new GraphicsUse(null, 2L);
        var secondClaim = producer.retain();
        abandoned.whenComplete(secondClaim::close);
        producer.close();
        abandoned.resolveSubmission(() -> { throw new AssertionError(); }, pending::add);
        assertEquals(0, destroyed.get());
        assertEquals(1, pending.size());

        pending.removeFirst().run();
        assertEquals(1, destroyed.get());
    }
}
