package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderPassManagerTest {
    private static final GpuImage FAKE_IMAGE =
            new GpuImage(0L, null, 0L, 0L, 0L, 1, 1, 0, 1, 0, "fake");

    @Test
    void ordersByStageThenTopologicallyWithinAStage() {
        FakePass sky = new FakePass("sky_lut", RenderStage.ENVIRONMENT_PREPARE);
        FakePass bloom = new FakePass("bloom", RenderStage.AFTER_RECONSTRUCTION);
        FakePass overlay = new FakePass("overlay", RenderStage.OVERLAY);
        // Declared out of dependency order on purpose: b depends on a, but a is registered second.
        FakePass b = new FakePass("b", RenderStage.LOOK, List.of(identifierOf("a")));
        FakePass a = new FakePass("a", RenderStage.LOOK);

        List<CausticaRenderPass> ordered = RenderPassManager.orderPasses(
                List.of(overlay, b, bloom, a, sky));

        assertEquals(List.of(sky, bloom, a, b, overlay), ordered);
    }

    @Test
    void independentPassesInTheSameStageAreOrderedDeterministicallyById() {
        FakePass z = new FakePass("z", RenderStage.LOOK);
        FakePass a = new FakePass("a", RenderStage.LOOK);

        assertEquals(List.of(a, z), RenderPassManager.orderPasses(List.of(z, a)));
    }

    @Test
    void afterReferencingAnUnknownOrDifferentStagePassIsIgnored() {
        FakePass onlyOne = new FakePass("only", RenderStage.LOOK, List.of(identifierOf("nonexistent")));

        assertEquals(List.of(onlyOne), RenderPassManager.orderPasses(List.of(onlyOne)));
    }

    @Test
    void aCycleInAfterThrows() {
        FakePass a = new FakePass("a", RenderStage.LOOK, List.of(identifierOf("b")));
        FakePass b = new FakePass("b", RenderStage.LOOK, List.of(identifierOf("a")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RenderPassManager.orderPasses(List.of(a, b)));
        assertTrue(e.getMessage().contains("cycle"));
    }

    // The chain rotation itself needs a real command buffer (each link ends in a barrier), so what is
    // checkable here is the state it starts from: with nothing chained, the display map has to read the
    // reconstruction rather than a post target holding last frame's contents.
    @Test
    void anEmptyPostChainLeavesTheSceneAtTheReconstruction() {
        GpuImage reconstruction = new GpuImage(1L, null, 0L, 0L, 0L, 1, 1, 0, 1, 0, "reconstruction");
        RenderPassManager manager = new RenderPassManager(null, List.of());
        manager.setReconstructedColor(reconstruction);
        manager.setSceneColorTargets(FAKE_IMAGE, FAKE_IMAGE);

        manager.beginFrame();

        assertEquals(reconstruction, manager.sceneColor());
    }

    @Test
    void theWorldResourceGenerationTracksPublishesAndRollbacks() {
        FakePass publishes = new FakePass("publishes", RenderStage.ENVIRONMENT_PREPARE) {
            @Override
            public void resize(PassSetup setup, int width, int height) {
                setup.publishWorldResource("skyView", FAKE_IMAGE, 1L);
            }
        };
        RenderPassManager manager = new RenderPassManager(null, List.of(publishes));
        int initial = manager.worldResourceGeneration();

        manager.resize(100, 100);
        int afterPublish = manager.worldResourceGeneration();
        assertNotEquals(initial, afterPublish, "a publish must be visible to a rebinding consumer");

        manager.resize(100, 100); // same extent: resize() short-circuits, nothing republishes
        assertEquals(afterPublish, manager.worldResourceGeneration());

        manager.resize(200, 200);
        assertNotEquals(afterPublish, manager.worldResourceGeneration());
    }

    @Test
    void rollingBackAFailedPassAlsoMovesTheWorldResourceGeneration() {
        FakePass publishesThenThrows = new FakePass("throws", RenderStage.ENVIRONMENT_PREPARE) {
            @Override
            public void resize(PassSetup setup, int width, int height) {
                setup.publishWorldResource("skyView", FAKE_IMAGE, 1L);
                throw new RuntimeException("boom");
            }
        };
        RenderPassManager manager = new RenderPassManager(null, List.of(publishesThenThrows));

        manager.resize(100, 100);

        // Publish then unpublish: the consumer must see a change, not the value it started from, or it
        // would keep a descriptor pointing at an image the failed pass's cleanup already freed.
        assertEquals(Map.of(), manager.worldResources());
        assertNotEquals(0, manager.worldResourceGeneration());
    }

    @Test
    void aFailedPassIsDestroyedOnlyOnce() {
        AtomicInteger destroys = new AtomicInteger();
        FakePass failing = new FakePass("throws", RenderStage.ENVIRONMENT_PREPARE) {
            @Override
            public void resize(PassSetup setup, int width, int height) {
                throw new RuntimeException("boom");
            }

            @Override
            public void destroy() {
                destroys.incrementAndGet();
            }
        };
        RenderPassManager manager = new RenderPassManager(null, List.of(failing));

        manager.resize(100, 100);
        manager.destroy();
        manager.destroy();

        assertEquals(1, destroys.get());
    }

    private static ResourceId identifierOf(String path) {
        return ResourceId.of("caustica", path);
    }

    private static class FakePass implements CausticaRenderPass {
        private final ResourceId id;
        private final RenderStage stage;
        private final List<ResourceId> after;

        FakePass(String path, RenderStage stage) {
            this(path, stage, List.of());
        }

        FakePass(String path, RenderStage stage, List<ResourceId> after) {
            this.id = identifierOf(path);
            this.stage = stage;
            this.after = after;
        }

        @Override
        public ResourceId id() {
            return id;
        }

        @Override
        public RenderStage stage() {
            return stage;
        }

        @Override
        public List<ResourceId> after() {
            return after;
        }

        @Override
        public void record(PassFrame frame) {
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }
}
