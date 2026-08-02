package dev.comfyfluffy.caustica.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SlangRuntimeManifestTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void parsesValidatedManifest() throws IOException {
        SlangRuntimeManifest manifest = SlangRuntimeManifest.read(new StringReader("""
                {
                  "format": 1,
                  "abi": 2,
                  "slangVersion": "2026.8",
                  "platform": "windows-x64",
                  "shim": "causticaslang.dll",
                  "bundleSha256": "%s",
                  "files": [
                    {"path":"causticaslang.dll","size":42,"sha256":"%s"}
                  ]
                }
                """.formatted(HASH, HASH)));

        assertEquals("2026.8", manifest.slangVersion());
        assertEquals("causticaslang.dll", manifest.files().getFirst().path());
    }

    @Test
    void rejectsTraversalAndDuplicatePaths() {
        assertThrows(IOException.class, () -> readWithFiles("""
                {"path":"../causticaslang.dll","size":42,"sha256":"%s"}
                """.formatted(HASH)));
        assertThrows(IOException.class, () -> readWithFiles("""
                {"path":"causticaslang.dll","size":42,"sha256":"%s"},
                {"path":"causticaslang.dll","size":42,"sha256":"%s"}
                """.formatted(HASH, HASH)));
    }

    private static SlangRuntimeManifest readWithFiles(String files) throws IOException {
        return SlangRuntimeManifest.read(new StringReader("""
                {
                  "format": 1,
                  "abi": 2,
                  "slangVersion": "2026.8",
                  "platform": "windows-x64",
                  "shim": "causticaslang.dll",
                  "bundleSha256": "%s",
                  "files": [%s]
                }
                """.formatted(HASH, files)));
    }
}
