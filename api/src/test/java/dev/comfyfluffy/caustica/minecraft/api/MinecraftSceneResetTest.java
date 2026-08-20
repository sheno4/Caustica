package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftSceneResetTest {
    @Test
    void clearsEveryLocalSourceBeforeRequestingAnyEngineReset() {
        List<String> order = new ArrayList<>();
        var first = MinecraftSceneReset.register(scope(() -> order.add("request-first")),
                () -> order.add("clear-first"));
        var second = MinecraftSceneReset.register(scope(() -> order.add("request-second")),
                () -> order.add("clear-second"));
        try {
            MinecraftSceneReset.request();
            assertEquals(List.of("clear-first", "clear-second", "request-first", "request-second"), order);

            order.clear();
            first.close();
            first.close();
            MinecraftSceneReset.request();
            assertEquals(List.of("clear-second", "request-second"), order);
        } finally {
            first.close();
            second.close();
        }
    }

    private static SceneScope scope(Runnable reset) {
        return new SceneScope() {
            @Override
            public void submit(SceneGeometryKey groupKey, List<SceneGeometrySink.Operation> operations,
                               Runnable onPublished) { }

            @Override
            public void requestSceneReset() {
                reset.run();
            }
        };
    }
}
