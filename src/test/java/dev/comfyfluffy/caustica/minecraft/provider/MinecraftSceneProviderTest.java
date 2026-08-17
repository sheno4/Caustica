package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.SceneCamera;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftSceneProviderTest {
    @Test
    void frameSubmissionDrainsTerrainBeforeCapturingEntitiesThroughTheSameSink() {
        List<String> order = new ArrayList<>();
        SceneGeometrySink sink = new SceneGeometrySink() {
            @Override
            public void submit(SceneGeometryKey groupKey, List<Operation> operations,
                               java.util.function.Consumer<Publication> onPublished) {
            }
        };
        SceneFrameContext frame = new SceneFrameContext(sink, 0, 0, 0, 7L, SceneCamera.IDENTITY);

        MinecraftSceneProvider.submitFrameGeometry(frame, geometry -> {
            assertSame(sink, geometry);
            order.add("terrain");
        }, entityFrame -> {
            assertSame(frame, entityFrame);
            order.add("entities");
        });

        assertEquals(List.of("terrain", "entities"), order);
    }

    @Test
    void providerStopInvalidatesEntityProfilingBeforeStoppingWorkers() {
        List<String> order = new ArrayList<>();

        MinecraftSceneProvider.stopSources(() -> order.add("entities"), () -> order.add("workers"));

        assertEquals(List.of("entities", "workers"), order);
    }
}
