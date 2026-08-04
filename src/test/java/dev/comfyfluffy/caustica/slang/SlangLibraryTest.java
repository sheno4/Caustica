package dev.comfyfluffy.caustica.slang;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlangLibraryTest {
    private static SlangLibrary library;
    private static MemorySegment runtime;

    @BeforeAll
    static void loadRuntime() {
        Path directory = Path.of(System.getProperty("caustica.test.slangRuntimeDir"));
        library = SlangLibrary.load(directory, SlangPlatform.current());
        assertEquals(SlangLibrary.ABI_VERSION, library.abiVersion());
        assertTrue(library.compilerVersion().startsWith("2026.8"));
        runtime = library.createRuntime();
    }

    @AfterAll
    static void destroyRuntime() {
        if (library != null && runtime != null) {
            library.destroyRuntime(runtime);
        }
    }

    @Test
    void compilesValidatedSpirvAndReflection(@TempDir Path sourceDirectory) {
        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_DEBUG_INFO | SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompileResult result = library.compile(session, "test_module", "test_module.slang", """
                    RWStructuredBuffer<uint> output;

                    [shader("compute")]
                    [numthreads(1, 1, 1)]
                    void main(uint3 dispatchThreadId : SV_DispatchThreadID)
                    {
                        output[dispatchThreadId.x] = 42;
                    }
                    """, "main");

            byte[] spirv = result.spirv();
            assertTrue(spirv.length > 20);
            assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertFalse(result.reflectionJson().isBlank());
            assertTrue(result.reflectionJson().contains("output"));
        } finally {
            library.destroySession(session);
        }
    }

    @Test
    void returnsSourceDiagnostics(@TempDir Path sourceDirectory) {
        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompilationException failure = assertThrows(SlangCompilationException.class,
                    () -> library.compile(session, "broken_module", "broken_module.slang", """
                            [shader("compute")]
                            [numthreads(1, 1, 1)]
                            void main() { this is not Slang; }
                            """, "main"));
            assertFalse(failure.diagnostics().isBlank());
            assertTrue(failure.diagnostics().contains("broken_module.slang"));
        } finally {
            library.destroySession(session);
        }
    }

    @Test
    void dynamicallySpecializesAnEngineEntryPointWithAnImplementation(@TempDir Path sourceDirectory)
            throws Exception {
        Files.writeString(sourceDirectory.resolve("test_api.slang"), """
                module test_api;
                public interface ITestPack {
                    public uint value();
                }
                """);
        Files.writeString(sourceDirectory.resolve("test_pack.slang"), """
                module test_pack;
                import test_api;
                public struct TestPack : ITestPack {
                    public uint value() { return 42u; }
                };
                """);
        Files.writeString(sourceDirectory.resolve("test_engine.slang"), """
                module test_engine;
                import test_api;
                RWStructuredBuffer<uint> output;

                [shader("compute")]
                [numthreads(1, 1, 1)]
                void main<TImplementation : ITestPack>(uint3 id : SV_DispatchThreadID) {
                    TImplementation implementation;
                    output[id.x] = implementation.value();
                }
                """);

        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompileResult result = library.compileSpecialized(session,
                    "test_engine", "main", "test_pack", "TestPack");
            assertEquals(0x07230203,
                    ByteBuffer.wrap(result.spirv()).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertTrue(result.reflectionJson().contains("output"));
        } finally {
            library.destroySession(session);
        }
    }

    @Test
    void specializesAnEngineEntryPointWithAssociatedCompositionTypes(@TempDir Path sourceDirectory)
            throws Exception {
        Files.writeString(sourceDirectory.resolve("composition_api.slang"), """
                module composition_api;
                public interface ISkyModel { public uint skyValue(); }
                public interface ISurfaceModel { public uint surfaceValue(); }
                public interface IComposition {
                    associatedtype Sky : ISkyModel;
                    associatedtype Surface : ISurfaceModel;
                }
                """);
        Files.writeString(sourceDirectory.resolve("composition_models.slang"), """
                module composition_models;
                import composition_api;
                public struct TestSky : ISkyModel { public uint skyValue() { return 19u; } }
                public struct TestSurface : ISurfaceModel { public uint surfaceValue() { return 23u; } }
                """);
        Files.writeString(sourceDirectory.resolve("test_composition.slang"), """
                module test_composition;
                import composition_api;
                import composition_models;
                public struct TestComposition : IComposition {
                    public typealias Sky = TestSky;
                    public typealias Surface = TestSurface;
                }
                """);
        Files.writeString(sourceDirectory.resolve("composition_engine.slang"), """
                module composition_engine;
                import composition_api;
                RWStructuredBuffer<uint> output;
                [shader("compute")]
                [numthreads(1, 1, 1)]
                void main<TC : IComposition>(uint3 id : SV_DispatchThreadID) {
                    TC.Sky sky;
                    TC.Surface surface;
                    output[id.x] = sky.skyValue() + surface.surfaceValue();
                }
                """);

        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompileResult result = library.compileSpecialized(session,
                    "composition_engine", "main", "test_composition", "TestComposition");
            assertEquals(0x07230203,
                    ByteBuffer.wrap(result.spirv()).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertTrue(result.reflectionJson().contains("output"));
        } finally {
            library.destroySession(session);
        }
    }

    @Test
    void rejectsAnImplementationAuthoredShaderEntryPoint(@TempDir Path sourceDirectory) throws Exception {
        Files.writeString(sourceDirectory.resolve("entry_api.slang"), """
                module entry_api;
                public interface IEntryPack {
                    public uint value();
                }
                """);
        Files.writeString(sourceDirectory.resolve("entry_pack.slang"), """
                module entry_pack;
                import entry_api;
                public struct EntryPack : IEntryPack {
                    public uint value() { return 1u; }
                };
                [shader("compute")]
                [numthreads(1, 1, 1)]
                void implementationMain() {}
                """);
        Files.writeString(sourceDirectory.resolve("entry_engine.slang"), """
                module entry_engine;
                import entry_api;
                [shader("compute")]
                [numthreads(1, 1, 1)]
                void main<TImplementation : IEntryPack>() {
                    TImplementation implementation;
                    implementation.value();
                }
                """);

        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompilationException failure = assertThrows(SlangCompilationException.class,
                    () -> library.compileSpecialized(session,
                            "entry_engine", "main", "entry_pack", "EntryPack"));
            assertTrue(failure.diagnostics().contains("must not define shader entry points"));
        } finally {
            library.destroySession(session);
        }
    }

    private static Path resourceDirectory(String resource) throws Exception {
        return Path.of(Objects.requireNonNull(SlangLibraryTest.class.getResource(resource), resource).toURI())
                .getParent();
    }
}
