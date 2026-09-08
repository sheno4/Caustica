package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.settings.OptionValues;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShowcasePassContractTest {
    @Test
    void postPlacementUsesTheProvenStageLocalBloomAnchor() {
        var post = (PassPlacement.After) ShowcasePasses.POST_EFFECT_PLACEMENT;

        assertSame(ShowcasePasses.BLOOM, post.anchor());
    }

    @Test
    void worldResourcePassWaitsForProgramsAndPublishesOnce() {
        AtomicBoolean ready = new AtomicBoolean();
        var publication = new java.util.concurrent.CompletableFuture<Void>();
        var publishCalls = new java.util.concurrent.atomic.AtomicInteger();
        var pass = ShowcasePasses.worldResource(ready::get, () -> {
            publishCalls.incrementAndGet();
            return publication;
        });

        pass.record(null);
        assertEquals(0, publishCalls.get());
        ready.set(true);
        pass.record(null);
        pass.record(null);
        assertEquals(1, publishCalls.get());
        publication.complete(null);
        pass.record(null);
        assertEquals(1, publishCalls.get());
        pass.close();
    }

    @Test
    void worldResourcePassCancelsPendingPublicationOnClose() {
        var publication = new java.util.concurrent.CompletableFuture<Void>();
        var pass = ShowcasePasses.worldResource(() -> true, () -> publication);
        pass.record(null);
        pass.close();
        assertTrue(publication.isCancelled());
    }

    @Test
    void worldResourcePassReportsPublicationFailure() {
        var publication = new java.util.concurrent.CompletableFuture<Void>();
        var pass = ShowcasePasses.worldResource(() -> true, () -> publication);
        pass.record(null);
        var failure = new IllegalStateException("publication failed");
        publication.completeExceptionally(failure);
        var reported = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class, () -> pass.record(null));
        assertSame(failure, reported.getCause());
        pass.close();
    }

    @Test
    void settingsReadUsesAFrameStableLookupSnapshot() {
        AtomicBoolean snapshotted = new AtomicBoolean();
        OptionLookup values = feature -> new OptionValues() {
            @Override public <T> T get(Option<T> option) {
                assertSame(ApiShowcaseExtension.ID, feature);
                assertSame(ApiShowcaseExtension.COLOUR_GRADE_STRENGTH, option);
                @SuppressWarnings("unchecked") T value = (T) Float.valueOf(0.75f);
                return value;
            }
        };
        OptionLookup lookup = new OptionLookup() {
            @Override public dev.comfyfluffy.caustica.settings.OptionValues options(
                    dev.comfyfluffy.caustica.settings.ResourceId featureId) {
                throw new AssertionError("live lookup must not be read directly");
            }

            @Override public OptionLookup snapshot() {
                snapshotted.set(true);
                return values;
            }
        };

        assertEquals(0.75f, ShowcasePasses.colourGradeStrength(lookup));
        assertTrue(snapshotted.get());
    }

    @Test
    void uiSceneBindingReadsTheDescriptorIndexAtTheStartOfPushData() {
        var mapping = ShowcasePasses.UI_SCENE_MAPPING;
        assertEquals(0, mapping.descriptorSet());
        assertEquals(0, mapping.binding());
        assertEquals(0, mapping.pushDataOffset());
    }
}
