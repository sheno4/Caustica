package dev.comfyfluffy.caustica.rt.shader;

import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CompositionTest {
    private static final ProgramComposition PROGRAM = new ProgramComposition(1, List.of());

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

    @Test
    void managerPublishesAtomicallyAndWaitsForRetirement() {
        Composition first = composition("first", Map.of());
        Composition second = composition("second", Map.of());
        CompositionManager manager = new CompositionManager(first);
        manager.activate(second);
        assertEquals(second, manager.current());
        assertEquals(first, manager.retiringOrNull());
        assertThrows(IllegalStateException.class, () -> manager.activate(first));
        manager.retireCompleted();
        assertNull(manager.retiringOrNull());
    }

    private static Composition composition(String root, Map<String, byte[]> sources) {
        return Composition.create(PROGRAM, Map.of(), List.of(), "test_composition", "Composition", root, sources);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
