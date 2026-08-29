package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

final class RetainedSceneProgressQueueTest {
    @Test
    void completionIsVisibleBeforeItsProgressSignal() {
        RetainedSceneProgressQueue<Object> completions = new RetainedSceneProgressQueue<>();
        AtomicReference<Object> observed = new AtomicReference<>();
        Object completion = new Object();
        completions.onProgressAvailable(() -> observed.set(completions.poll()));

        completions.add(completion);

        assertSame(completion, observed.get());
    }

    @Test
    void installingSignalWakesForAnAlreadyVisibleCompletion() {
        RetainedSceneProgressQueue<Object> completions = new RetainedSceneProgressQueue<>();
        AtomicReference<Object> observed = new AtomicReference<>();
        Object completion = new Object();
        completions.add(completion);

        completions.onProgressAvailable(() -> observed.set(completions.poll()));

        assertSame(completion, observed.get());
    }
}
