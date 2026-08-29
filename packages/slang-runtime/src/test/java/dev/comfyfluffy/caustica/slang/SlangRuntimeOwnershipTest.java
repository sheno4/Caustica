package dev.comfyfluffy.caustica.slang;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlangRuntimeOwnershipTest {
    @Test
    void runtimeHasNoGlobalInstanceAndRequiresFilesystemConfiguration() throws Exception {
        assertFalse(Arrays.stream(SlangRuntime.class.getDeclaredFields())
                .anyMatch(field -> Modifier.isStatic(field.getModifiers())
                        && field.getType() == SlangRuntime.class));
        assertTrue(Modifier.isPublic(SlangRuntime.class
                .getConstructor(SlangRuntimeConfig.class).getModifiers()));

        var config = new SlangRuntimeConfig(Path.of("compiler-cache"), Optional.empty());
        assertTrue(config.extractionRoot().isAbsolute());
    }
}
