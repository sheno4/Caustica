package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ResourceOwnersTest {
    @Test
    void closeReleasesEveryCapturedClaimOnceDespiteASharedFailure() {
        var releases = new AtomicInteger();
        var failure = new IllegalStateException("shared cleanup failure");
        var first = source(() -> { releases.incrementAndGet(); throw failure; });
        var second = source(() -> { releases.incrementAndGet(); throw failure; });
        var third = source(releases::incrementAndGet);
        var owners = ResourceOwners.capture(List.of(first, second, first, third));

        assertSame(failure, assertThrows(IllegalStateException.class, owners::close));
        owners.close();
        assertEquals(3, releases.get());
        assertEquals(0, failure.getSuppressed().length);
        assertThrows(IllegalStateException.class, () -> owners.retain(third));
    }

    @Test
    void failedCapturePreservesRetainFailureAndReleasesEarlierClaims() {
        var releases = new AtomicInteger();
        var original = new IllegalArgumentException("retain failed");
        var cleanup = new IllegalStateException("cleanup failed");
        var first = source(() -> { releases.incrementAndGet(); throw cleanup; });
        var rejected = new ResourceOwner() {
            @Override public ResourceOwner retain() { throw original; }
            @Override public void close() { throw new AssertionError("unaccepted owner"); }
        };

        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> ResourceOwners.capture(List.of(first, rejected))));
        assertEquals(1, releases.get());
        assertEquals(List.of(cleanup), List.of(original.getSuppressed()));
    }

    private static ResourceOwner source(Runnable release) {
        return new ResourceOwner() {
            @Override public ResourceOwner retain() {
                return new ResourceOwner() {
                    @Override public ResourceOwner retain() { throw new AssertionError("unexpected retain"); }
                    @Override public void close() { release.run(); }
                };
            }
            @Override public void close() { throw new AssertionError("borrowed source"); }
        };
    }
}
