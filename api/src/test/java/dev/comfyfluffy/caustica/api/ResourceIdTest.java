package dev.comfyfluffy.caustica.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceIdTest {
    @Test
    void acceptsTheCompleteComponentAlphabetsAndBoundaryPunctuation() {
        String namespace = "abcdefghijklmnopqrstuvwxyz0123456789_.-";
        String path = "abcdefghijklmnopqrstuvwxyz0123456789/._-";

        assertEquals(new ResourceId(namespace, path), ResourceId.of(namespace, path));
        assertEquals(ResourceId.of("-._", "/._-"), ResourceId.parse("-._:/._-"));
        assertEquals("/", ResourceId.of("a", "/").path());
        assertInvalidNamespace("a/b");
    }

    @Test
    void rejectsEmptyAndNullComponentsWithStableMessages() {
        assertInvalidNamespace("");
        assertInvalidPath("");
        assertEquals("namespace", assertThrows(NullPointerException.class,
                () -> new ResourceId(null, "path")).getMessage());
        assertEquals("path", assertThrows(NullPointerException.class,
                () -> ResourceId.of("namespace", null)).getMessage());
        assertEquals("invalid resource namespace: ", assertThrows(IllegalArgumentException.class,
                () -> ResourceId.of("", "path")).getMessage());
        assertEquals("invalid resource path: ", assertThrows(IllegalArgumentException.class,
                () -> ResourceId.of("namespace", "")).getMessage());
    }

    @Test
    void rejectsCharactersOutsideTheAsciiLanguage() {
        for (String invalid : new String[] {"Upper", "two words", "a/b", "a\\b", "a:b", "caf\u00e9", "a\u0000b",
                "a\uD800b", "a\uDC00b"}) {
            assertInvalidNamespace(invalid);
        }
        for (String invalid : new String[] {"Upper", "two words", "a\\b", "a:b", "caf\u00e9", "a\u0000b",
                "a\uD800b", "a\uDC00b"}) {
            assertInvalidPath(invalid);
        }
    }

    @Test
    void asciiBoundaryMatchesTheDeclaredLanguages() {
        for (char c = 0; c < 128; c++) {
            String component = "a" + c + "b";
            boolean namespaceAllowed = lowerAscii(c) || digit(c) || c == '_' || c == '.' || c == '-';
            boolean pathAllowed = namespaceAllowed || c == '/';
            if (namespaceAllowed) {
                assertEquals(component, ResourceId.of(component, "path").namespace());
            } else {
                assertThrows(IllegalArgumentException.class, () -> ResourceId.of(component, "path"));
            }
            if (pathAllowed) {
                assertEquals(component, ResourceId.of("namespace", component).path());
            } else {
                assertThrows(IllegalArgumentException.class, () -> ResourceId.of("namespace", component));
            }
        }
    }

    @Test
    void parseRequiresExactlyOneInteriorColon() {
        ResourceId id = ResourceId.parse("pack:models/actor");
        assertEquals("pack", id.namespace());
        assertEquals("models/actor", id.path());

        for (String invalid : new String[] {"pack", ":path", "pack:", "pack:path:extra", "PACK:path", "pack:Path"}) {
            assertNull(ResourceId.tryParse(invalid));
            assertEquals("invalid resource id: " + invalid, assertThrows(IllegalArgumentException.class,
                    () -> ResourceId.parse(invalid)).getMessage());
        }
        assertNull(ResourceId.tryParse(null));
        assertEquals("invalid resource id: null", assertThrows(IllegalArgumentException.class,
                () -> ResourceId.parse(null)).getMessage());
    }

    @Test
    void equalityRemainsARecordValueContract() {
        ResourceId first = ResourceId.of("test", "same/path");
        ResourceId second = new ResourceId("test", "same/path");
        ResourceId third = ResourceId.parse("test:same/path");

        assertTrue(first.equals(first));
        assertTrue(first.equals(second));
        assertTrue(second.equals(first));
        assertTrue(second.equals(third));
        assertTrue(first.equals(third));
        assertFalse(first.equals(null));
        assertFalse(first.equals("test:same/path"));
        assertNotEquals(first, ResourceId.of("other", "same/path"));
        assertNotEquals(first, ResourceId.of("test", "other/path"));
    }

    @Test
    void hashCodeMatchesTheJdkRecordFoldIncludingSignedOverflow() {
        ResourceId ordinary = ResourceId.of("test", "same/path");
        assertEquals(31 * ordinary.namespace().hashCode() + ordinary.path().hashCode(), ordinary.hashCode());

        ResourceId overflow = ResourceId.of("zzzzzzzzzzzzzzzz", "aaaaaaaaaaaaaaaaaaaa");
        int expected = 31 * overflow.namespace().hashCode() + overflow.path().hashCode();
        assertTrue(expected < 0);
        assertEquals(expected, overflow.hashCode());
        assertEquals(ordinary.hashCode(), ResourceId.parse("test:same/path").hashCode());
    }

    @Test
    void hashCollectionsDeduplicateAndReplaceEqualIds() {
        ResourceId first = ResourceId.of("test", "same/path");
        ResourceId equal = ResourceId.parse("test:same/path");
        HashSet<ResourceId> set = new HashSet<>();
        assertTrue(set.add(first));
        assertFalse(set.add(equal));
        assertEquals(1, set.size());

        HashMap<ResourceId, Integer> map = new HashMap<>();
        map.put(first, 7);
        assertEquals(7, map.put(equal, 9));
        assertEquals(1, map.size());
        assertEquals(9, map.get(first));
    }

    @Test
    void comparisonUsesTheRenderedIdentifierIncludingPrefixOrdering() {
        ResourceId prefix = ResourceId.of("a", "b");
        ResourceId extension = ResourceId.of("a", "ba");
        ResourceId laterNamespace = ResourceId.of("b", "a");

        assertTrue(prefix.compareTo(extension) < 0);
        assertTrue(extension.compareTo(laterNamespace) < 0);
        assertEquals(0, prefix.compareTo(ResourceId.parse("a:b")));
        assertEquals("a:b", prefix.toString());

        TreeSet<ResourceId> sorted = new TreeSet<>();
        sorted.add(laterNamespace);
        sorted.add(extension);
        sorted.add(prefix);
        sorted.add(ResourceId.parse("a:b"));
        assertEquals(java.util.List.of(prefix, extension, laterNamespace), java.util.List.copyOf(sorted));
    }

    private static boolean lowerAscii(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static void assertInvalidNamespace(String value) {
        assertEquals("invalid resource namespace: " + value, assertThrows(IllegalArgumentException.class,
                () -> ResourceId.of(value, "path")).getMessage());
    }

    private static void assertInvalidPath(String value) {
        assertEquals("invalid resource path: " + value, assertThrows(IllegalArgumentException.class,
                () -> ResourceId.of("namespace", value)).getMessage());
    }
}
