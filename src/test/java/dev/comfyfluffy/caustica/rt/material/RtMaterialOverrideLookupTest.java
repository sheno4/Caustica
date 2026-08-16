package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class RtMaterialOverrideLookupTest {
    private static final ResourceId MATERIAL = ResourceId.of("test", "material");
    private static final ResourceId GEOMETRY = ResourceId.of("test", "geometry");

    @Test
    void earlierGeometryRuleWinsOverALaterDuplicate() {
        int[] first = {1};
        int[] duplicate = {2};
        RtMaterialRegistry.CompiledOverrideLookup lookup = RtMaterialRegistry.CompiledOverrideLookup.of(List.of(
                entry(MATERIAL, GEOMETRY, first), entry(MATERIAL, GEOMETRY, duplicate)));

        assertArrayEquals(first, lookup.resolve(MATERIAL, GEOMETRY));
    }

    @Test
    void geometryRulesAreSelectedIndependently() {
        ResourceId otherGeometry = ResourceId.of("test", "other_geometry");
        int[] first = {1};
        int[] second = {2};
        RtMaterialRegistry.CompiledOverrideLookup lookup = RtMaterialRegistry.CompiledOverrideLookup.of(List.of(
                entry(MATERIAL, GEOMETRY, first), entry(MATERIAL, otherGeometry, second)));

        assertArrayEquals(first, lookup.resolve(MATERIAL, GEOMETRY));
        assertArrayEquals(second, lookup.resolve(MATERIAL, otherGeometry));
    }

    @Test
    void lookupDoesNotInspectRulesForAnotherMaterial() {
        RtMaterialRegistry.CompiledOverrideLookup lookup = RtMaterialRegistry.CompiledOverrideLookup.of(List.of(
                entry(MATERIAL, GEOMETRY, new int[]{1})));

        assertNull(lookup.resolve(ResourceId.of("test", "other"), GEOMETRY));
    }

    private static RtMaterialRegistry.CompiledOverrideLookup.Entry entry(ResourceId material,
                                                                          ResourceId geometry,
                                                                          int[] variants) {
        return new RtMaterialRegistry.CompiledOverrideLookup.Entry(material, geometry, variants);
    }
}
