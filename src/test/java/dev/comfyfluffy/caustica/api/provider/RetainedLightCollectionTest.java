package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RetainedLightCollectionTest {
    @Test
    void collectionOwnsBothGroupAndLightLists() {
        ArrayList<LightDescriptor.Finite> lights = new ArrayList<>();
        lights.add(new LightDescriptor.Point(1L, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0));
        RetainedLightCollection.Group group = new RetainedLightCollection.Group(9L, 11L, lights);
        ArrayList<RetainedLightCollection.Group> groups = new ArrayList<>(List.of(group));

        RetainedLightCollection collection = new RetainedLightCollection(13L, groups);
        lights.clear();
        groups.clear();

        assertEquals(13L, collection.generation());
        assertEquals(1, collection.groups().size());
        assertEquals(1, collection.groups().getFirst().lights().size());
        assertThrows(UnsupportedOperationException.class, () -> collection.groups().add(group));
        assertThrows(UnsupportedOperationException.class, () -> group.lights().add(null));
    }
}
