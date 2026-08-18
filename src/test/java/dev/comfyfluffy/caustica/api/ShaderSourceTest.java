package dev.comfyfluffy.caustica.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShaderSourceTest {
    @Test
    void resolvesModulesThroughTheContributingClassAnchor() throws IOException {
        ShaderSource source = ShaderSource.classpath(
                ShaderSourceTest.class, "/caustica-test/shaders");

        assertSame(ShaderSourceTest.class, source.resourceAnchor());
        try (var module = source.openModule("test_surface")) {
            assertNotNull(module);
        }
    }

    @Test
    void rejectsUnnormalizedSubdirectories() {
        assertThrows(IllegalArgumentException.class, () -> ShaderSource.classpath(
                ShaderSourceTest.class, "/caustica-test/shaders", "../foreign"));
    }
}
