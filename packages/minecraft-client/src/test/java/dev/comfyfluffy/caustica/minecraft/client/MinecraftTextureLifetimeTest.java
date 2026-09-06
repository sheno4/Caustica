package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftTextureLifetimeTest {
    @Test void workerReleasesKeepTheHostLeaseUntilTheRenderThreadDrainsThem() throws Exception {
        var releases = new MinecraftTextureLifetime.Releases();
        var hostThread = Thread.currentThread();
        var views = new AtomicInteger(3);
        var released = new ArrayList<Integer>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                for (int index = 1; index <= 2; index++) {
                    int id = index;
                    releases.add(() -> {
                        assertSame(hostThread, Thread.currentThread());
                        views.decrementAndGet();
                        released.add(id);
                    });
                }
            }).get();
        }
        assertEquals(3, views.get());
        assertTrue(released.isEmpty());
        // Closing the host's own claim cannot destroy an image still retained by queued releases.
        views.decrementAndGet();
        assertEquals(2, views.get());
        releases.drain();
        assertEquals(List.of(1, 2), released);
        assertEquals(0, views.get());
        releases.drain();
        assertEquals(List.of(1, 2), released);
        assertEquals(0, views.get());
    }
}
