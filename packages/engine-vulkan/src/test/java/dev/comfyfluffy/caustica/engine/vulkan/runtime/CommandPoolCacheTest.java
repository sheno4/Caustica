package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

final class CommandPoolCacheTest {
    @Test
    void acceptedGraphicsUseReturnsOnlyAfterCompletion() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        var first = cache.acquire(1);
        int command = first.begin(0);
        nativeCalls.pending.add(command / 1000);
        var use = new GraphicsUse(null, 1L);
        use.keepAlive(first);
        use.commandsAccepted();
        List<Runnable> completion = new ArrayList<>();
        use.resolveSubmission(() -> {}, completion::add);

        var second = cache.acquire(1);
        assertNotEquals(command, second.begin(0));
        assertEquals(0, cache.stats().resets());
        nativeCalls.pending.clear();
        completion.getFirst().run();
        var reused = cache.acquire(1);
        assertEquals(command, reused.begin(0));
        assertEquals(2, cache.stats().created());
        assertEquals(1, cache.stats().resets());
        assertEquals(List.of("reset:1", "begin:1000"), nativeCalls.events.subList(6, 8));
    }

    @Test
    void staleCompletionCannotReturnALaterLease() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        var old = cache.acquire(1);
        int command = old.begin(0);
        old.close();
        var current = cache.acquire(1);
        assertEquals(command, current.begin(0));
        old.close();
        assertNotEquals(command, cache.acquire(1).begin(0));
    }

    @Test
    void abandonedUseReturnsWithoutASignal() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        var lease = cache.acquire(1);
        int command = lease.begin(0);
        var use = new GraphicsUse(null, 1L);
        use.keepAlive(lease);
        use.resolveSubmission(() -> fail("abandoned use must not signal"));
        assertEquals(command, cache.acquire(1).begin(0));
    }

    @Test
    void oneUseKeepsEveryAcceptedSlotUntilCompletionDespiteCallbackFailure() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        var first = cache.acquire(1);
        var second = cache.acquire(1);
        first.begin(0);
        second.begin(0);
        var use = new GraphicsUse(null, 1L);
        use.keepAlive(() -> { throw new IllegalStateException("resource release"); });
        use.keepAlive(first);
        use.keepAlive(second);
        use.commandsAccepted();
        List<Runnable> completion = new ArrayList<>();
        use.resolveSubmission(() -> {}, completion::add);
        assertEquals(0, cache.stats().resets());
        assertThrows(IllegalStateException.class, completion.getFirst()::run);
        cache.acquire(1).begin(0);
        cache.acquire(1).begin(0);
        assertEquals(2, cache.stats().created());
        assertEquals(2, cache.stats().resets());
    }

    @Test
    void computeGrowthRetainsFiveBuffersAcrossTwoFiveOne() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "compute", 128);
        for (int count : List.of(2, 5, 1)) {
            var lease = cache.acquire(count);
            for (int i = 0; i < count; i++) assertEquals(1000 + i, lease.begin(i));
            nativeCalls.pending.add(1);
            // The batch's successful timeline wait establishes completion before return.
            nativeCalls.pending.clear();
            lease.close();
        }
        assertEquals(new CommandPoolCache.Stats(1, 2, 0, 5), cache.stats());
        assertEquals(List.of("allocate:1:2", "allocate:1:3"), nativeCalls.events.stream()
                .filter(event -> event.startsWith("allocate:")).toList());
        assertThrows(IllegalArgumentException.class, () -> cache.acquire(129));
    }

    @Test
    void failedLeaseIsRetainedButNeverRecycled() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "compute", 128);
        var failed = cache.acquire(2);
        failed.begin(0);
        failed.fail();
        failed.close();
        assertEquals(2000, cache.acquire(1).begin(0));
        assertEquals(0, cache.stats().destroyed());
        cache.destroyAfterDeviceIdle();
        assertEquals(Set.of(1, 2), nativeCalls.destroyed);
    }

    @Test
    void failedResetAllocationAndBeginKeepNativePoolsUntilShutdown() {
        for (String operation : List.of("reset", "allocate", "begin")) {
            var nativeCalls = new NativeCalls();
            var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
            if (operation.equals("reset")) cache.acquire(1).close();
            nativeCalls.failOperation = operation;
            assertThrows(IllegalStateException.class, () -> cache.acquire(1).begin(0));
            nativeCalls.failOperation = null;
            assertEquals(2000, cache.acquire(1).begin(0));
            assertTrue(nativeCalls.destroyed.isEmpty());
            cache.destroyAfterDeviceIdle();
            assertEquals(Set.of(1, 2), nativeCalls.destroyed);
        }
    }

    @Test
    void shutdownDestroysEveryPoolOnceIncludingOutstandingLeases() {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        var outstanding = cache.acquire(1);
        cache.acquire(1).close();
        cache.destroyAfterDeviceIdle();
        outstanding.close();
        cache.destroyAfterDeviceIdle();
        assertEquals(new CommandPoolCache.Stats(2, 0, 2, 2), cache.stats());
        assertThrows(IllegalStateException.class, () -> cache.acquire(1));
    }

    @Test
    void completionThreadOnlyPublishesTokensAndWarmReusePlateaus() throws Exception {
        var nativeCalls = new NativeCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        try (var retirement = Executors.newSingleThreadExecutor()) {
            for (int i = 0; i < 100; i++) {
                var lease = cache.acquire(1);
                assertEquals(1000, lease.begin(0));
                retirement.submit(lease::close).get();
            }
        }
        assertEquals(new CommandPoolCache.Stats(1, 99, 0, 1), cache.stats());
        cache.destroyAfterDeviceIdle();
    }

    @Test
    void staleOwnedCommandBufferCloseCannotReturnNewRecording() {
        var nativeCalls = new NullCommandCalls();
        var cache = new CommandPoolCache<>(nativeCalls, "graphics", 1);
        var old = new OwnedCommandBuffer(null, cache.acquire(1));
        old.close();
        var current = new OwnedCommandBuffer(null, cache.acquire(1));
        old.close();
        assertThrows(IllegalStateException.class, old::commandBuffer);
        assertThrows(IllegalStateException.class, old::end);
        new OwnedCommandBuffer(null, cache.acquire(1));
        assertEquals(2, cache.stats().created());
        current.close();
        cache.destroyAfterDeviceIdle();
    }

    private static final class NativeCalls implements CommandPoolCache.Backend<Integer> {
        final Thread recordingThread = Thread.currentThread();
        final List<String> events = new ArrayList<>();
        final Set<Integer> pending = new HashSet<>();
        final Set<Integer> destroyed = new HashSet<>();
        final List<Integer> capacities = new ArrayList<>(List.of(0));
        String failOperation;

        private void call(String operation, String event) {
            assertSame(recordingThread, Thread.currentThread(), "native pool access changed threads");
            events.add(event);
            if (operation.equals(failOperation)) throw new IllegalStateException(operation);
        }

        public long createPool() {
            int pool = capacities.size();
            call("create", "create:" + pool);
            capacities.add(0);
            return pool;
        }
        public List<Integer> allocate(long pool, int count) {
            call("allocate", "allocate:" + pool + ":" + count);
            int id = (int) pool;
            List<Integer> commands = new ArrayList<>();
            for (int i = 0; i < count; i++) commands.add(id * 1000 + capacities.get(id) + i);
            capacities.set(id, capacities.get(id) + count);
            return commands;
        }
        public void reset(long pool) {
            assertFalse(pending.contains((int) pool), "reset while GPU commands are pending");
            call("reset", "reset:" + pool);
        }
        public void begin(Integer command) { call("begin", "begin:" + command); }
        public void destroy(long pool) {
            assertFalse(pending.contains((int) pool));
            assertTrue(destroyed.add((int) pool), "pool destroyed twice");
            call("destroy", "destroy:" + pool);
        }
    }

    private static final class NullCommandCalls implements CommandPoolCache.Backend<VkCommandBuffer> {
        long next;
        public long createPool() { return ++next; }
        public List<VkCommandBuffer> allocate(long pool, int count) { return java.util.Collections.nCopies(count, null); }
        public void reset(long pool) {}
        public void begin(VkCommandBuffer command) {}
        public void destroy(long pool) {}
    }
}
