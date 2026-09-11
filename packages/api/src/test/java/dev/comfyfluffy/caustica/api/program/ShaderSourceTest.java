package dev.comfyfluffy.caustica.api.program;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShaderSourceTest {
    @Test
    void resolvesModulesThroughTheContributingClassAnchor() throws IOException {
        ShaderSource source = ShaderSource.classpath(
                ShaderSourceTest.class, "/caustica/shaders/api");

        assertSame(ShaderSourceTest.class, source.resourceAnchor());
        ShaderDefinition definition = source.definition("caustica_api", "Example");
        assertSame(source, definition.source());
        try (var module = source.openModule("caustica_api")) {
            assertNotNull(module);
        }
    }

    @Test
    void resolvesCompoundSlangModuleNamesAsNestedResourcePaths() throws IOException {
        ShaderSource source = ShaderSource.classpath(
                ShaderSourceTest.class, "/caustica/shaders");

        try (var module = source.openModule("api.caustica_api")) {
            assertNotNull(module);
        }
    }

    @Test
    void acceptsQualifiedTypesAndRejectsTraversalShapedNames() {
        ShaderSource source = ShaderSource.classpath(ShaderSourceTest.class, "/caustica/shaders/api");

        new ShaderDefinition(source, "caustica_api", "Environment");
        new ShaderDefinition(source, "caustica_api", "comfyfluffy.example.Environment");
        assertThrows(IllegalArgumentException.class, () -> source.openModule("example..foreign"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderDefinition(source, "../foreign", "example.Environment"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderDefinition(source, "example", "example..Environment"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderDefinition(source, "example", "example::Environment"));
    }

    @Test
    void rejectsUnnormalizedSubdirectories() {
        assertThrows(IllegalArgumentException.class, () -> ShaderSource.classpath(
                ShaderSourceTest.class, "/caustica-test/shaders", "../foreign"));
    }
}
