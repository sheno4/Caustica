package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

final class RtMeshPreparerTest {
    @Test void completedBuildReleasesScratchBeforePublishingReadyOwnership() {
        List<String> events = new ArrayList<>();
        var owner = owner(events);
        var result = new CompletableFuture<ResourceOwner>();
        result.thenAccept(ready -> events.add("ready"));
        RtMeshPreparer.finish(result, owner, () -> events.add("scratch"), new GpuComputeCompletion.Succeeded());
        assertSame(owner, result.join());
        assertEquals(List.of("scratch", "ready"), events);
        result.join().close();
        assertEquals(List.of("scratch", "ready", "destroy"), events);
    }

    @Test void cancellingTheConsumerDoesNotCancelAcceptedGpuOwnership() {
        List<String> events = new ArrayList<>();
        var result = new CompletableFuture<ResourceOwner>();
        var owner = owner(events);
        result.cancel(false);
        assertEquals(List.of(), events);
        RtMeshPreparer.finish(result, owner, () -> events.add("scratch"), new GpuComputeCompletion.Succeeded());
        assertEquals(List.of("scratch", "destroy"), events);
        assertTrue(result.isCancelled());
    }

    @Test void failedBuildNeverPublishesTheDestination() {
        List<String> events = new ArrayList<>();
        var result = new CompletableFuture<ResourceOwner>();
        var failure = new IllegalStateException("device");
        RtMeshPreparer.finish(result, owner(events), () -> events.add("scratch"), new GpuComputeCompletion.Failed(failure));
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        assertEquals(List.of("scratch", "destroy"), events);
    }

    @Test void scratchFailureStillReleasesDestinationOwnership() {
        List<String> events = new ArrayList<>();
        var result = new CompletableFuture<ResourceOwner>();
        var failure = new IllegalStateException("scratch");
        RtMeshPreparer.finish(result, owner(events), () -> { throw failure; }, new GpuComputeCompletion.Succeeded());
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        assertEquals(List.of("destroy"), events);
    }

    @Test void compactedMeshPublishesOnlyAfterCopyCompletionAndSourceRelease() {
        List<String> events = new ArrayList<>();
        var source = owner(events, "source");
        var destination = owner(events, "compacted");
        var result = new CompletableFuture<ResourceOwner>();
        result.thenAccept(ready -> events.add("ready"));
        assertFalse(result.isDone());
        assertEquals(List.of(), events);
        RtMeshPreparer.finishCompaction(result, source, destination, new GpuComputeCompletion.Succeeded());
        assertSame(destination, result.join());
        assertEquals(List.of("source", "ready"), events);
        result.join().close();
        assertEquals(List.of("source", "ready", "compacted"), events);
    }

    @Test void cancelledCompactionConsumerRetainsBothGenerationsUntilGpuCompletion() {
        List<String> events = new ArrayList<>();
        var result = new CompletableFuture<ResourceOwner>();
        var source = owner(events, "source");
        var destination = owner(events, "compacted");
        result.cancel(false);
        assertEquals(List.of(), events);
        RtMeshPreparer.finishCompaction(result, source, destination, new GpuComputeCompletion.Succeeded());
        assertEquals(List.of("source", "compacted"), events);
        assertTrue(result.isCancelled());
    }

    @Test void failedCompactionReleasesBothGenerationsWithoutPublishing() {
        List<String> events = new ArrayList<>();
        var result = new CompletableFuture<ResourceOwner>();
        var failure = new IllegalStateException("copy");
        RtMeshPreparer.finishCompaction(result, owner(events, "source"), owner(events, "compacted"),
                new GpuComputeCompletion.Failed(failure));
        assertEquals(List.of("source", "compacted"), events);
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    @Test void destinationAllocationFailureStillReleasesUncompactedSource() {
        List<String> events = new ArrayList<>();
        var result = new CompletableFuture<ResourceOwner>();
        var failure = new IllegalStateException("allocation");
        RtMeshPreparer.finishCompaction(result, owner(events, "source"), null,
                new GpuComputeCompletion.Failed(failure));
        assertEquals(List.of("source"), events);
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    private static ResourceOwner owner(List<String> events) {
        return owner(events, "destroy");
    }

    private static ResourceOwner owner(List<String> events, String label) {
        return new ResourceOwner() {
            public ResourceRef reference() { return ResourceRef.none(); }
            public ResourceOwner retain() { throw new UnsupportedOperationException(); }
            public void close() { events.add(label); }
        };
    }
}
