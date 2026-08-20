package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialSurfaceResolutionTest {
    @Test
    void errorSurfaceIsTheOnlyReservedImplementation() {
        assertEquals(0, RtMaterialRegistry.ERROR_SURFACE_IMPLEMENTATION);
    }

    @Test
    void unregisteredSurfacesResolveToTheErrorImplementation() {
        assertEquals(0, RtMaterialRegistry.resolveSurfaceImplementation(
                definition(ResourceId.of("test", "missing")), ignored -> -1));
    }

    @Test
    void registeredAndProbeRejectedSurfacesKeepTheResolverDecision() {
        ResourceId surface = ResourceId.of("test", "surface");

        assertEquals(1, RtMaterialRegistry.resolveSurfaceImplementation(definition(surface), ignored -> 1));
        assertEquals(0, RtMaterialRegistry.resolveSurfaceImplementation(definition(surface), ignored -> 0));
    }

    private static MaterialDefinition definition(ResourceId surface) {
        return new MaterialDefinition(new MaterialHandle(ResourceId.of("test", "material")),
                1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.5f, 0.0f,
                MaterialTopology.SURFACE, surface);
    }
}
