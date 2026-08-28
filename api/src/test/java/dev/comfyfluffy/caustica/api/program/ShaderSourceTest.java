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
        try (var module = source.openModule("caustica_api")) {
            assertNotNull(module);
        }
    }

    @Test
    void rejectsUnnormalizedSubdirectories() {
        assertThrows(IllegalArgumentException.class, () -> ShaderSource.classpath(
                ShaderSourceTest.class, "/caustica-test/shaders", "../foreign"));
    }
}
