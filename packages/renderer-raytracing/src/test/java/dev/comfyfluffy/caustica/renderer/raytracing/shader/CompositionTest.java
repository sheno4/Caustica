package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class CompositionTest {
    @Test
    void contentHashCoversSourceNamesBytesAndGeneratedRoot() {
        Composition first = composition("root-a", Map.of("a.slang", bytes("same")));
        Composition same = composition("root-a", Map.of("a.slang", bytes("same")));
        Composition changedRoot = composition("root-b", Map.of("a.slang", bytes("same")));
        Composition changedSource = composition("root-a", Map.of("a.slang", bytes("different")));
        Composition changedName = composition("root-a", Map.of("b.slang", bytes("same")));

        assertEquals(first.contentHash(), same.contentHash());
        assertNotEquals(first.contentHash(), changedRoot.contentHash());
        assertNotEquals(first.contentHash(), changedSource.contentHash());
        assertNotEquals(first.contentHash(), changedName.contentHash());
    }

    private static Composition composition(String root, Map<String, byte[]> sources) {
        return Composition.create(Map.of(), List.of(), "test_composition", "Composition", root, sources);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
