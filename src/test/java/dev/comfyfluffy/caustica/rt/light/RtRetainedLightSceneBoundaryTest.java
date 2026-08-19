package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import dev.comfyfluffy.caustica.api.ResourceId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtRetainedLightSceneBoundaryTest {
    @Test
    void retainedInputCopiesBothBatchAndLightLists() {
        ArrayList<LightDescriptor.Finite> lights = new ArrayList<>();
        RetainedLightBatch batch = new RetainedLightBatch(ResourceId.of("test", "source"), 3L, 5L, lights);
        ArrayList<RetainedLightBatch> batches = new ArrayList<>(List.of(batch));
        RetainedLightSnapshot snapshot = new RetainedLightSnapshot(batches, 7L);

        lights.clear();
        batches.clear();

        assertEquals(1, snapshot.batches().size());
        assertEquals(0, snapshot.batches().getFirst().lights().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.batches().add(batch));
        assertThrows(UnsupportedOperationException.class,
                () -> batch.lights().add(null));
        assertEquals(7, snapshot.generation());
        assertEquals(3L, batch.key());
        assertEquals(5L, batch.revision());
    }

    @Test
    void stoppingSceneStopsItsOwnedScheduler() {
        AtomicInteger stops = new AtomicInteger();
        RtRetainedLightScene scene = new RtRetainedLightScene(new RtRetainedLightScene.TaskScheduler() {
            @Override
            public void submit(Runnable task, Runnable cancelled) {
                throw new AssertionError("no task expected");
            }

            @Override
            public void shutdown() {
                stops.incrementAndGet();
            }
        });

        scene.stopCpuWork();

        assertEquals(1, stops.get());
    }

}
