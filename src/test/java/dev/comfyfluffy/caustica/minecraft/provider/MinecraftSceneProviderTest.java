package dev.comfyfluffy.caustica.minecraft.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftSceneProviderTest {
    @Test
    void providerStopInvalidatesEntityProfilingBeforeStoppingWorkers() {
        List<String> order = new ArrayList<>();

        MinecraftSceneProvider.stopSources(() -> order.add("entities"), () -> order.add("workers"));

        assertEquals(List.of("entities", "workers"), order);
    }
}
