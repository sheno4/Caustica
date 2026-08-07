package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aPassThatThrowsAfterPublishingHasThatOutputRolledBack() {
        FakePass publishesThenThrows = new FakePass("throws", RenderStage.AFTER_RECONSTRUCTION) {
            @Override
            public void resize(PassSetup setup, int width, int height) {
                setup.publishOutput("bloom", FAKE_IMAGE, 1);
                throw new RuntimeException("boom");
            }
        };
        RenderPassManager manager = new RenderPassManager(null, List.of(publishesThenThrows), 0L);

        manager.resize(100, 100);

        assertFalse(manager.hasOutput("bloom"),
                "an output published just before a throw must not be left dangling");
    }

    @Test
    void aPassThatPublishesSuccessfullyKeepsItsOutputBound() {
        FakePass publishes = new FakePass("publishes", RenderStage.AFTER_RECONSTRUCTION) {
            @Override
            public void resize(PassSetup setup, int width, int height) {
                setup.publishOutput("bloom", FAKE_IMAGE, 1);
            }
        };
        RenderPassManager manager = new RenderPassManager(null, List.of(publishes), 0L);

        manager.resize(100, 100);

        assertTrue(manager.hasOutput("bloom"));
        assertEquals(FAKE_IMAGE, manager.output("bloom"));
    }

    private static Identifier identifierOf(String path) {
        return Identifier.fromNamespaceAndPath("caustica", path);
    }

    private static class FakePass implements CausticaRenderPass {
        private final Identifier id;
        private final RenderStage stage;
        private final List<Identifier> after;

        FakePass(String path, RenderStage stage) {
            this(path, stage, List.of());
        }

        FakePass(String path, RenderStage stage, List<Identifier> after) {
            this.id = identifierOf(path);
            this.stage = stage;
            this.after = after;
        }

        @Override
        public Identifier id() {
            return id;
        }

        @Override
        public RenderStage stage() {
            return stage;
        }

        @Override
        public List<Identifier> after() {
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
