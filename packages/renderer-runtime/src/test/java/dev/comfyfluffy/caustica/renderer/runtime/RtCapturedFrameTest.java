package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RtCapturedFrameTest {
    @Test void replacementBeforeRecordingPreservesCapturedProgramAndScene() {
        var destroyed = new AtomicInteger();
        var program = program(destroyed);
        var scene = SharedResource.owned(new RetainedSceneSnapshot(7, List.of(), List.of(), List.of(), List.of()),
                ignored -> destroyed.incrementAndGet());
        var inputs = inputs();
        var captured = SharedResource.owned(RtCapturedFrame.capture(inputs, program::retain, scene::retain, () -> 0L),
                RtCapturedFrame::close);
        program.close();
        scene.close();
        assertEquals(0, destroyed.get());
        assertSame(inputs, captured.get().inputs());
        assertEquals(7, captured.get().scenes().get().revision());

        var execution = captured.retain();
        captured.close();
        assertEquals(0, destroyed.get());
        execution.close();
        assertEquals(2, destroyed.get());
    }

    @Test void failedSceneCaptureReleasesTheAcquiredProgram() {
        var destroyed = new AtomicInteger();
        var program = program(destroyed);
        assertThrows(IllegalStateException.class, () -> RtCapturedFrame.capture(inputs(), program::retain,
                () -> { throw new IllegalStateException("scene capture failed"); }, () -> 0L));
        program.close();
        assertEquals(1, destroyed.get());
    }

    @Test void capturedMediumCanBeRetainedAfterProducerValuesClose() {
        var destroyed = new AtomicInteger();
        try (var resources = new dev.comfyfluffy.caustica.engine.resource.ResourceDirectory(failure -> fail(failure))) {
            var dependency = resources.openFactory(new dev.comfyfluffy.caustica.engine.session.ContributionOwner(1))
                    .create(destroyed::incrementAndGet);
            var type = dev.comfyfluffy.caustica.api.program.ShaderDataType.<Object>create("medium");
            var binding = type.data(123, dependency);
            var instance = type.data(456, dependency);
            var medium = new dev.comfyfluffy.caustica.api.view.ViewMedium.Volume<>(
                    new dev.comfyfluffy.caustica.api.program.VolumeId<Object, Object>() { }, binding, instance);
            var inputs = new FrameSnapshot(new SceneView(new SceneId() { }, Camera.IDENTITY, medium),
                    new SceneOrigin(0, 0, 0), false, 0, 1);
            var scene = SharedResource.owned(new RetainedSceneSnapshot(1, List.of(), List.of(), List.of(), List.of()),
                    ignored -> { });
            var captured = RtCapturedFrame.capture(inputs, () -> null, scene::retain, () -> 0L);
            binding.close();
            instance.close();
            dependency.close();
            scene.close();
            assertThrows(IllegalStateException.class, binding::retain);
            var retainedMedium = (dev.comfyfluffy.caustica.api.view.ViewMedium.Volume<?, ?>) captured.inputs().view().medium();
            var reader = retainedMedium.bindingData().retain();
            assertEquals(123, reader.bits());
            captured.close();
            resources.awaitRetirements();
            assertEquals(0, destroyed.get());
            reader.close();
            resources.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void publicationsAfterSceneCaptureWaitForTheNextFrame() {
        var telemetry = new RtTelemetryImpl();
        var visible = new java.util.ArrayList<String>();
        try (var scene = SharedResource.owned(
                new RetainedSceneSnapshot(1, List.of(), List.of(), List.of(), List.of()), ignored -> { })) {
            telemetry.beginRenderFrame();
            telemetry.afterPublicationVisible(frame -> visible.add("before:" + frame));
            try (var captured = RtCapturedFrame.capture(inputs(), () -> null, scene::retain,
                    telemetry::publicationCutoff)) {
                telemetry.afterPublicationVisible(frame -> visible.add("after:" + frame));
                telemetry.frameAssembled(captured.publicationCutoff());
                assertEquals(List.of("before:1"), visible);
            }
            telemetry.endFrame();
            telemetry.beginRenderFrame();
            try (var captured = RtCapturedFrame.capture(inputs(), () -> null, scene::retain,
                    telemetry::publicationCutoff)) {
                telemetry.frameAssembled(captured.publicationCutoff());
                assertEquals(List.of("before:1", "after:2"), visible);
            }
        }
    }

    private static FrameSnapshot inputs() {
        return new FrameSnapshot(new SceneView(new SceneId() { }, Camera.IDENTITY),
                new SceneOrigin(0, 0, 0), false, 0, 1);
    }

    private static SharedResource<RtProgramBackend.Published> program(AtomicInteger destroyed) {
        return SharedResource.owned(new RtProgramBackend.Published() {
            @Override public RtPipeline pipeline() { throw new AssertionError("capture must not record"); }
            @Override public VulkanDeviceAddress compositionDataAddress() { throw new AssertionError(); }
            @Override public void close() { throw new AssertionError("release through shared ownership"); }
        }, ignored -> destroyed.incrementAndGet());
    }
}
