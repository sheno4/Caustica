package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import dev.comfyfluffy.caustica.rt.gen.BloomPushData;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BloomPassTest {
    @Test
    void planIsOnePrefilterThenDownsampleBottomUpThenUpsampleTopDown() {
        List<BloomPass.Step> steps = BloomPass.plan(4);

        assertEquals(7, steps.size());
        assertEquals(List.of(0, 1, 1, 1, 2, 2, 2), steps.stream().map(BloomPass.Step::mode).toList());
        assertEquals(List.of(0, 1, 2, 3, 2, 1, 0),
                steps.stream().map(BloomPass.Step::destinationLevel).toList());
        // Prefilter reads the engine's reconstructed colour (encoded as source level -1), not a pyramid level.
        assertEquals(-1, steps.get(0).sourceLevel());
        assertEquals(List.of(-1, 0, 1, 2, 3, 2, 1),
                steps.stream().map(BloomPass.Step::sourceLevel).toList());
    }

    @Test
    void singleLevelPyramidIsOnePrefilterStepOnly() {
        List<BloomPass.Step> steps = BloomPass.plan(1);

        assertEquals(1, steps.size());
        assertEquals(0, steps.get(0).mode());
        assertEquals(0, steps.get(0).destinationLevel());
    }

    // The composite step reads the finished pyramid from level 0, so level 0 is what the pyramid has to
    // finish on -- at every depth, including the single-level pyramid that never upsamples.
    @Test
    void theLastPyramidStepAlwaysWritesTheLevelTheCompositeReads() {
        for (int levelCount = 1; levelCount <= 8; levelCount++) {
            List<BloomPass.Step> steps = BloomPass.plan(levelCount);
            assertEquals(0, steps.get(steps.size() - 1).destinationLevel(),
                    "level count " + levelCount);
        }
    }

    @Test
    void dispatchGroupCountRoundsUpToWholeGroups() {
        assertEquals(120, BloomPass.groups(960));
        assertEquals(1, BloomPass.groups(1));
        assertEquals(1, BloomPass.groups(8));
        assertEquals(2, BloomPass.groups(9));
    }

    @Test
    void pyramidSizingMatchesThePreviousBloomExtentPolicy() {
        assertEquals(8, BloomPass.levelCount(1920, 1080, 8, 8));
        assertEquals(4, BloomPass.levelCount(64, 64, 8, 8));
        assertEquals(1, BloomPass.levelCount(8, 64, 8, 8));
        assertEquals(4, BloomPass.levelCount(1920, 1080, 4, 8));
    }

    @Test
    void runtimeShaderCompilesAndBuildDoesNotPackageABloomSpirv(@TempDir Path cache) throws Exception {
        Identifier id = Identifier.fromNamespaceAndPath("caustica", "bloom");
        PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(cache, id,
                ShaderSource.classpath("/caustica/shaders/builtin", "bloom"), "caustica_bloom", "main");

        assertEquals(0x07230203, ByteBuffer.wrap(compiled.spirv())
                .order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(Files.isRegularFile(cache.resolve("caustica/bloom/caustica_bloom.slang")));
        assertNotNull(getClass().getResource(
                "/caustica/shaders/builtin/bloom/caustica_bloom.slang"));
        assertNull(getClass().getResource(
                "/caustica/shaders/pipelines/bloom/main.comp.spv"));

        PassShaderCompiler.validateBindings(id, compiled.reflectionJson(),
                List.of(ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.SAMPLED,
                        ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.STORAGE),
                BloomPushData.BYTE_SIZE, "main", 8, 8, 1);
    }
}
