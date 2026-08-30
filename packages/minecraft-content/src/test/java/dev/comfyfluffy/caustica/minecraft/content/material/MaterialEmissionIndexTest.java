package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MaterialEmissionIndexTest {
    @Test
    void freezesAdapterResultsAndAnswersByHostNeutralResourceId() {
        ResourceId torch = ResourceId.parse("minecraft:block/torch");
        HashMap<ResourceId, Integer> source = new HashMap<>();
        source.put(torch, 14);

        MaterialEmissionIndex index = new MaterialEmissionIndex(source, 3, 1);
        source.clear();

        assertTrue(index.permits(torch));
        assertEquals(14, index.maxEmission(torch));
        assertFalse(index.permits(ResourceId.parse("minecraft:block/stone")));
        assertEquals(0, index.maxEmission(ResourceId.parse("minecraft:block/stone")));
        assertThrows(UnsupportedOperationException.class,
                () -> index.maxEmission().put(ResourceId.parse("test:new"), 1));
    }
}
