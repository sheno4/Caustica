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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The appearance-composition path has to emit ray-tracing stages, not just compute, before slot code
 * can run inside the real world pipeline: the first engine entry point specialized with an implementation
 * is the sky miss shader. This pins that the pinned compiler/profile actually produces a miss stage with
 * the ray-tracing capability, so a regression shows up here rather than at vkCreateRayTracingPipelinesKHR.
 */
final class SlangRaytracingStageTest {
    private static final int SPIRV_MAGIC = 0x07230203;
    private static SlangLibrary library;
    private static MemorySegment runtime;

    @BeforeAll
    static void loadRuntime() {
        library = SlangLibrary.load(Path.of(System.getProperty("caustica.test.slangRuntimeDir")),
                SlangPlatform.current());
        runtime = library.createRuntime();
    }

    @AfterAll
    static void destroyRuntime() {
        if (library != null && runtime != null) {
            library.destroyRuntime(runtime);
        }
    }

    @Test
    void specializesAMissShaderEntryPointWithAnImplementation(@TempDir Path sourceDirectory) throws Exception {
        Files.writeString(sourceDirectory.resolve("miss_api.slang"), """
                module miss_api;
                public struct MissEnvironmentQuery {
                    public float3 direction;
                };
                public interface IMissPack {
                    public float3 evaluateEnvironment(MissEnvironmentQuery query);
                }
                """);
        Files.writeString(sourceDirectory.resolve("miss_pack.slang"), """
                module miss_pack;
                import miss_api;
                public struct ConstantSkyPack : IMissPack {
                    public float3 evaluateEnvironment(MissEnvironmentQuery query) {
                        return float3(0.05, 0.18, 0.65);
                    }
                };
                """);
        // Mirrors the shape the real sky miss shader would take: an engine-owned [shader("miss")] entry
        // point, generic over the implementation type, writing an engine-owned payload it never sees.
        Files.writeString(sourceDirectory.resolve("miss_engine.slang"), """
                module miss_engine;
                import miss_api;

                public struct EnginePayload {
                    public float3 radiance;
                    public float hitT;
                };

                [shader("miss")]
                    void main<TEnvironment : IMissPack>(inout EnginePayload payload) {
                    MissEnvironmentQuery query;
                    query.direction = normalize(WorldRayDirection());
                    TEnvironment environment;
                    payload.radiance = environment.evaluateEnvironment(query);
                    payload.hitT = -1.0;
                }
                """);

        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompileResult result = library.compileSpecialized(session,
                    "miss_engine", "main", "miss_pack", "ConstantSkyPack");
            byte[] spirv = result.spirv();
            assertEquals(SPIRV_MAGIC,
                    ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertTrue(spirv.length > 20, "expected a non-trivial miss shader module");
        } finally {
            library.destroySession(session);
        }
    }
}
