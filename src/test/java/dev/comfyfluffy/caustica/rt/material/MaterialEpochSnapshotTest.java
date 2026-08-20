package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MaterialEpochSnapshotTest {
    @Test
    void exposesOnlyCompiledSurfaceAvailability() {
        ResourceId available = ResourceId.parse("test:available");
        ResourceId rejected = ResourceId.parse("test:rejected");
        ResourceId error = ResourceId.parse("caustica:error_surface");
        MaterialEpochSnapshot snapshot = snapshot(Set.of(available));

        assertTrue(snapshot.surfaceAvailable(available));
        assertFalse(snapshot.surfaceAvailable(rejected));
        assertFalse(snapshot.surfaceAvailable(error));
        assertFalse(snapshot.surfaceAvailable(ResourceId.parse("test:unregistered")));
    }

    @Test
    void unknownNamedMaterialsNeverFallBackSilently() {
        MaterialEpochSnapshot snapshot = snapshot(Set.of());

        assertThrows(IllegalArgumentException.class,
                () -> snapshot.bindingId(ResourceId.parse("test:missing")));
    }

    private static MaterialEpochSnapshot snapshot(Set<ResourceId> availableSurfaces) {
        return new MaterialEpochSnapshot(1L, Map.of(), new int[0], Map.of(), availableSurfaces,
                List.of(),
                RtMaterialRegistry.CompiledOverrideLookup.of(List.of()), new int[]{0}, new byte[]{0});
    }
}
