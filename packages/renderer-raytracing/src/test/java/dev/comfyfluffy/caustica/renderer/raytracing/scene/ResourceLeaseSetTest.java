package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceLeaseSetTest {
    @Test
    void capturedGenerationSurvivesDropWhileNextCaptureSeesItUnavailable() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var channel = directory.openChannel(new ContributionOwner(1));
        AtomicInteger retired = new AtomicInteger();
        var generation = channel.create(retired::incrementAndGet);
        generation.seal();

        ResourceLeaseSet first = ResourceLeaseSet.capture(List.of(
                generation.reference(), generation.reference(), ResourceRef.none()));
        ResourceLeaseSet history = first.retainOnly(List.of(generation.reference()));
        generation.drop();
        ResourceLeaseSet second = ResourceLeaseSet.capture(List.of(generation.reference()));

        assertTrue(first.available(generation.reference()));
        assertTrue(first.available(ResourceRef.none()));
        assertFalse(second.available(generation.reference()));
        first.close();
        second.close();
        directory.progress();
        assertEquals(0, retired.get(), "motion history still owns the position generation");

        history.close();
        directory.progress();
        assertEquals(1, retired.get());
    }

    @Test
    void requiredAcquisitionReleasesEarlierLeaseWhenAnotherInputWasDropped() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var channel = directory.openChannel(new ContributionOwner(1));
        AtomicInteger firstRetired = new AtomicInteger();
        var first = channel.create(firstRetired::incrementAndGet);
        var dropped = channel.create();
        first.seal();
        dropped.seal();
        dropped.drop();

        assertThrows(IllegalStateException.class, () -> ResourceLeaseSet.acquireRequired(
                List.of(first.reference(), dropped.reference())));

        first.drop();
        directory.progress();
        assertEquals(1, firstRetired.get(),
                "failed transactional acquisition must release the first input");
    }
}
