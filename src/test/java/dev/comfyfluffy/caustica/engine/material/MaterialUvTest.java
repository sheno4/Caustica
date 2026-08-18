package dev.comfyfluffy.caustica.engine.material;

import dev.comfyfluffy.caustica.api.provider.MaterialUv;

import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MaterialUvTest {
    @Test
    void equalityRemainsARecordValueContract() {
        MaterialUv first = new MaterialUv(0.25f, 0.5f, 4f, 2f);
        MaterialUv second = new MaterialUv(0.25f, 0.5f, 4f, 2f);
        MaterialUv third = new MaterialUv(0.25f, 0.5f, 4f, 2f);

        assertTrue(first.equals(first));
        assertTrue(first.equals(second));
        assertTrue(second.equals(first));
        assertTrue(second.equals(third));
        assertTrue(first.equals(third));
        assertFalse(first.equals(null));
        assertFalse(first.equals("0.25,0.5,4,2"));
        assertNotEquals(first, new MaterialUv(0.125f, 0.5f, 4f, 2f));
        assertNotEquals(first, new MaterialUv(0.25f, 0.25f, 4f, 2f));
        assertNotEquals(first, new MaterialUv(0.25f, 0.5f, 8f, 2f));
        assertNotEquals(first, new MaterialUv(0.25f, 0.5f, 4f, 4f));
    }

    @Test
    void signedZeroUsesGeneratedRecordFloatSemanticsInEveryComponent() {
        MaterialUv positive = new MaterialUv(0f, 0f, 0f, 0f);
        for (int component = 0; component < 4; component++) {
            MaterialUv negative = withComponent(component, -0f);
            assertNotEquals(positive, negative);
            assertNotEquals(negative, positive);
            assertNotEquals(positive.hashCode(), negative.hashCode());
        }
    }

    @Test
    void hashCodeMatchesTheJdkRecordFoldIncludingSignedOverflow() {
        MaterialUv ordinary = new MaterialUv(0.25f, 0.5f, 4f, 2f);
        assertEquals(recordHash(ordinary), ordinary.hashCode());

        MaterialUv overflow = new MaterialUv(Float.MAX_VALUE, -Float.MAX_VALUE, 123.5f, 0f);
        int expected = recordHash(overflow);
        assertTrue(expected < 0);
        assertEquals(expected, overflow.hashCode());
        assertEquals(ordinary.hashCode(), new MaterialUv(0.25f, 0.5f, 4f, 2f).hashCode());
    }

    @Test
    void hashCollectionsDeduplicateAndReplaceEqualTransforms() {
        MaterialUv first = new MaterialUv(0.25f, 0.5f, 4f, 2f);
        MaterialUv equal = new MaterialUv(0.25f, 0.5f, 4f, 2f);
        HashSet<MaterialUv> set = new HashSet<>();
        assertTrue(set.add(first));
        assertFalse(set.add(equal));
        assertEquals(1, set.size());

        HashMap<MaterialUv, Integer> map = new HashMap<>();
        map.put(first, 7);
        assertEquals(7, map.put(equal, 9));
        assertEquals(1, map.size());
        assertEquals(9, map.get(first));
    }

    @Test
    void nestedAtlasReferencesRetainStructuralHashSemantics() {
        AtlasMaterialReference first = new AtlasMaterialReference(
                ResourceId.of("test", "material"), ResourceId.of("test", "atlas"),
                new MaterialUv(0.25f, 0.5f, 4f, 2f));
        AtlasMaterialReference equal = new AtlasMaterialReference(
                new ResourceId("test", "material"), new ResourceId("test", "atlas"),
                new MaterialUv(0.25f, 0.5f, 4f, 2f));

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        HashSet<AtlasMaterialReference> set = new HashSet<>();
        assertTrue(set.add(first));
        assertFalse(set.add(equal));
        HashMap<AtlasMaterialReference, Integer> map = new HashMap<>();
        map.put(first, 3);
        assertEquals(3, map.put(equal, 5));
        assertEquals(1, map.size());
        assertEquals(5, map.get(first));
    }

    @Test
    void rejectsEveryNonFiniteCategoryInEveryComponent() {
        float[] nonFinite = {
                Float.NaN,
                Float.intBitsToFloat(0x7f800001),
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY
        };
        for (int component = 0; component < 4; component++) {
            for (float value : nonFinite) {
                int selected = component;
                assertThrows(IllegalArgumentException.class, () -> withComponent(selected, value));
            }
        }
    }

    private static int recordHash(MaterialUv uv) {
        int result = Float.hashCode(uv.u());
        result = 31 * result + Float.hashCode(uv.v());
        result = 31 * result + Float.hashCode(uv.inverseDu());
        return 31 * result + Float.hashCode(uv.inverseDv());
    }

    private static MaterialUv withComponent(int component, float value) {
        float[] values = {0f, 0f, 0f, 0f};
        values[component] = value;
        return new MaterialUv(values[0], values[1], values[2], values[3]);
    }
}
