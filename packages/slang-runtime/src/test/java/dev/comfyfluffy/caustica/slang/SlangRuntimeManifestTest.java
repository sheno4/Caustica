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

    @Test
    void rejectsFractionalAndOverflowingFileSizes() {
        for (String size : new String[]{"42.5", "-0.5", "9223372036854775808", "18446744073709551658"}) {
            assertThrows(IOException.class, () -> readWithFiles("""
                    {"path":"causticaslang.dll","size":%s,"sha256":"%s"}
                    """.formatted(size, HASH)), size);
        }
    }

    @Test
    void acceptsExactIntegerScientificNotation() throws IOException {
        SlangRuntimeManifest manifest = readWithFiles("""
                {"path":"causticaslang.dll","size":4.2e1,"sha256":"%s"}
                """.formatted(HASH));

        assertEquals(42, manifest.files().getFirst().size());
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
