package dev.comfyfluffy.caustica.rt.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end cover for the appearance-composition path as the renderer will actually use it: the real
 * engine sky miss shader, importing the real world bindings/payload modules, specialized with the real
 * bundled pack — not a synthetic fixture. If the Pack API and the engine wrapper drift apart, or a
 * bundled source stops shipping, this fails here rather than at pipeline creation in-game.
 */
final class RayPackShaderCompilerTest {
    @Test
    void compilesTheEngineSkyMissShaderAgainstTheBundledPack(@TempDir Path cacheDirectory)
            throws Exception {
        RayPackEpoch epoch = RayPackEpoch.of(RayPackDiscovery.discoverBundled(),
                RayPackDiscovery.bundledManifestJson());

        try (RayPackShaderCompiler compiler = RayPackShaderCompiler.create(cacheDirectory)) {
            byte[] spirv = compiler.compileSkyMiss(epoch);

            assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertTrue(spirv.length > 1024,
                    "expected a substantial miss shader, got " + spirv.length + " bytes");
        }
    }

    @Test
    void repeatedCompilationOfTheSameEpochIsServedFromCache(@TempDir Path cacheDirectory)
            throws Exception {
        RayPackEpoch epoch = RayPackEpoch.of(RayPackDiscovery.discoverBundled(),
                RayPackDiscovery.bundledManifestJson());

        try (RayPackShaderCompiler compiler = RayPackShaderCompiler.create(cacheDirectory)) {
            byte[] first = compiler.compileSkyMiss(epoch);
            byte[] second = compiler.compileSkyMiss(epoch);

            assertArrayEquals(first, second);
            assertTrue(first == second, "second compile should return the cached array instance");
        }
    }
}
