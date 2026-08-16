package dev.comfyfluffy.caustica.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void recordValueHashAndCollectionsRemainStructural() {
        ResourceId first = ResourceId.of("test", "same/path");
        ResourceId equal = new ResourceId("test", "same/path");
        ResourceId different = ResourceId.of("test", "other/path");

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, different);
        assertTrue(new HashSet<>(java.util.List.of(first)).contains(equal));
        HashMap<ResourceId, Integer> map = new HashMap<>();
        map.put(first, 7);
        assertEquals(7, map.get(equal));
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
