package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.builtin.gen.BloomPushData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void pyramidSizingStopsAtTheConfiguredDepthOrMinimumExtent() {
        assertEquals(8, BloomPass.levelCount(1920, 1080, 8, 8));
        assertEquals(4, BloomPass.levelCount(64, 64, 8, 8));
        assertEquals(1, BloomPass.levelCount(8, 64, 8, 8));
        assertEquals(4, BloomPass.levelCount(1920, 1080, 4, 8));
    }

    @Test
    void generatedPushDataPublishesTypedHeapIndicesAtTheReflectedOffsets() {
        ByteBuffer data = ByteBuffer.allocate(BloomPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        new BloomPushData(11, 12, 13, 14, 15, 2, 3.0f, 4.0f, 5.0f, 6.0f).write(data);

        assertEquals(40, BloomPushData.BYTE_SIZE);
        assertEquals(11, data.getInt(0));
        assertEquals(12, data.getInt(4));
        assertEquals(13, data.getInt(8));
        assertEquals(14, data.getInt(12));
        assertEquals(15, data.getInt(16));
        assertEquals(2, data.getInt(20));
        assertEquals(6.0f, data.getFloat(36));
    }
}
