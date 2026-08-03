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

/**
 * The build-time shader task compiles indirect.rgen twice: once plainly, and once with
 * {@code -DCAUSTICA_ENABLE_EXT_SER -capability spvShaderInvocationReorderEXT}. The runtime shim ABI
 * accepts neither preprocessor defines nor capability flags, so moving that entry point to runtime
 * compilation depends on Slang inferring the reorder capability from the intrinsics a module actually
 * uses — which would let the SER variant be a separate module over a shared core instead of a
 * define-driven second compile of the same file.
 *
 * <p>This pins that inference. If it fails, the shim ABI has to grow a capability parameter before the
 * world raygen can be compiled at runtime.
 */
final class SlangSerCapabilityTest {
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
    void infersShaderInvocationReorderCapabilityWithoutAnExplicitFlag(@TempDir Path sourceDirectory)
            throws Exception {
        Files.writeString(sourceDirectory.resolve("ser_api.slang"), """
                module ser_api;
                public interface ISerPack {
                    public float3 shade(float3 direction);
                }
                """);
        Files.writeString(sourceDirectory.resolve("ser_pack.slang"), """
                module ser_pack;
                import ser_api;
                public struct SerPack : ISerPack {
                    public float3 shade(float3 direction) { return direction * 0.5 + 0.5; }
                };
                """);
        // Mirrors trace_ser.slang: HitObject::TraceRay + ReorderThread + HitObject::Invoke, with no
        // -capability on any command line, because the shim has no way to pass one.
        Files.writeString(sourceDirectory.resolve("ser_engine.slang"), """
                module ser_engine;
                import ser_api;

                public struct SerPayload { public float3 radiance; };

                RaytracingAccelerationStructure topLevelAS;
                RWTexture2D<float4> output;

                [shader("raygeneration")]
                void main<TPack : ISerPack>() {
                    uint2 pixel = DispatchRaysIndex().xy;
                    RayDesc ray;
                    ray.Origin = float3(0.0);
                    ray.TMin = 0.0;
                    ray.Direction = normalize(float3(float2(pixel) * 0.001 - 0.5, 1.0));
                    ray.TMax = 1000.0;

                    SerPayload payload;
                    payload.radiance = float3(0.0);
                    HitObject hit = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, 0xFFu,
                            0u, 1u, 0u, ray, payload);
                    ReorderThread(hit, 0u, 4u);
                    HitObject::Invoke(topLevelAS, hit, payload);

                    TPack pack;
                    output[pixel] = float4(payload.radiance + pack.shade(ray.Direction), 1.0);
                }
                """);

        MemorySegment session = library.createSession(runtime, List.of(sourceDirectory),
                SlangLibrary.SESSION_WARNINGS_AS_ERRORS);
        try {
            SlangCompileResult result = library.compileSpecialized(session,
                    "ser_engine", "main", "ser_pack", "SerPack");
            assertEquals(0x07230203,
                    ByteBuffer.wrap(result.spirv()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } finally {
            library.destroySession(session);
        }
    }
}
