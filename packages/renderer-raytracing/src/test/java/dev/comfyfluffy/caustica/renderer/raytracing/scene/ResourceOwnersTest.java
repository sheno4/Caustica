package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceOwnersTest {
    @Test
    void sharedFramesAndHistoryRetainResourcesAfterProducerRelease() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var channel = directory.openFactory(new ContributionOwner(1));
        AtomicInteger retired = new AtomicInteger();
        var generation = channel.create(retired::incrementAndGet);

        ResourceOwners first = ResourceOwners.capture(List.of(
                generation.reference(), generation.reference(), ResourceRef.none()));
        ResourceOwners history = first.retainOnly(List.of(generation.reference()));
        generation.close();
        ResourceOwners second = ResourceOwners.capture(List.of(generation.reference()));

        first.close();
        second.close();
        directory.awaitRetirements();
        assertEquals(0, retired.get(), "motion history still owns the position generation");

        history.close();
        directory.awaitRetirements();
        assertEquals(1, retired.get());
    }

    @Test
    void requiredAcquisitionReleasesEarlierLeaseWhenAnotherInputWasDropped() {
        ResourceDirectory directory = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        var channel = directory.openFactory(new ContributionOwner(1));
        AtomicInteger firstRetired = new AtomicInteger();
        var first = channel.create(firstRetired::incrementAndGet);
        var dropped = channel.create();
        dropped.close();

        assertThrows(IllegalStateException.class, () -> ResourceOwners.capture(
                List.of(first.reference(), dropped.reference())));

        first.close();
        directory.awaitRetirements();
        assertEquals(1, firstRetired.get(),
                "failed transactional acquisition must release the first input");
    }
}
