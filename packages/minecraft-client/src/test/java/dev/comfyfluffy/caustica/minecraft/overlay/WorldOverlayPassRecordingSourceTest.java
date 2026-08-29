package dev.comfyfluffy.caustica.minecraft.overlay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldOverlayPassRecordingSourceTest {
    @Test
    void recordsEveryMinecraftOverlayFeatureAndAlwaysRetiresFrameAllocations() throws IOException {
        String source = Files.readString(Path.of(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/overlay/WorldOverlayPass.java"));

        assertTrue(source.contains("new GlowOutlineFeature(entities)"));
        assertTrue(source.contains("new NameTagFeature(entities)"));
        assertTrue(source.contains("new BlockOutlineFeature(entities, terrain)"));
        assertTrue(source.contains("feature.prepare(device, framePool, gpuUse"));
        assertTrue(source.contains("recordDraws(frame.commandBuffer(), ready, frame.layer().view(), width, height)"));
        int finallyBlock = source.indexOf("finally {");
        int endFrame = source.indexOf("framePool.endFrame(gpuUse)", finallyBlock);
        assertTrue(finallyBlock >= 0 && endFrame > finallyBlock);
    }
}
