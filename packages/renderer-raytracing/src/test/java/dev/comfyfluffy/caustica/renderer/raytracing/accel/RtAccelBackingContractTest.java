package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtAccelBackingContractTest {
    @Test
    void addressBasedTransientBlasUsesAsyncSharedBacking() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/renderer/raytracing/accel/RtAccel.java"));
        String signature = "public static PreparedBlas prepareTransientBlas(VulkanDeviceContext ctx, VulkanDeviceAddress vertexAddr,";
        int start = source.indexOf(signature);
        int end = source.indexOf("/** Caller-owned classified BLAS", start);

        assertTrue(start >= 0 && end > start);
        assertTrue(source.substring(start, end).contains("ctx.createAsyncBuffer("));
    }
}
