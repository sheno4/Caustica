package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ResourceLifetimeTest {
    @Test
    void destructionRunsOnceInDependencyOrder() {
        List<String> destroyed = new ArrayList<>();
        ResourceLifetime lifetime = new ResourceLifetime(
                () -> destroyed.add("descriptor range"),
                () -> destroyed.add("native object"));

        lifetime.close();
        lifetime.close();

        assertEquals(List.of("descriptor range", "native object"), destroyed);
    }
}
