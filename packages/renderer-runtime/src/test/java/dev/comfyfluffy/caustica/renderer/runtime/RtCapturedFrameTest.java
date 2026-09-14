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
        var captured = SharedResource.owned(RtSceneRequest.capture(program::retain, scene::retain, () -> 0L, SceneOrigin.ZERO, 1),
                RtSceneRequest::close);
        program.close();
        scene.close();
        assertEquals(0, destroyed.get());
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
        assertThrows(IllegalStateException.class, () -> RtSceneRequest.capture(program::retain,
                () -> { throw new IllegalStateException("scene capture failed"); }, () -> 0L, SceneOrigin.ZERO, 1));
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
            var captured = RtCapturedFrame.capture(inputs);
            binding.close();
            instance.close();
            dependency.close();
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

    @Test void spatialMediumSurvivesProducerClosureWhenCameraIsInVacuum() {
        var destroyed = new AtomicInteger();
        try (var resources = new dev.comfyfluffy.caustica.engine.resource.ResourceDirectory(failure -> fail(failure))) {
            var dependency = resources.openFactory(new dev.comfyfluffy.caustica.engine.session.ContributionOwner(1))
                    .create(destroyed::incrementAndGet);
            var type = dev.comfyfluffy.caustica.api.program.ShaderDataType.<Object>create("spatial");
            var binding = type.data(123, dependency);
            var instance = type.data(456, dependency);
            var spatial = new dev.comfyfluffy.caustica.api.view.SpatialMedium<>(
                    new dev.comfyfluffy.caustica.api.program.VolumeId<Object, Object>() { }, binding, instance,
                    30000000.25, 64.5, -30000000.75,
                    dev.comfyfluffy.caustica.api.view.SpatialMedium.Transport.PATH_TRACED, 0.125f);
            var view = new SceneView(new SceneId() { }, Camera.IDENTITY,
                    dev.comfyfluffy.caustica.api.view.ViewMedium.Vacuum.INSTANCE, spatial);
            var captured = RtCapturedFrame.capture(new FrameSnapshot(view, SceneOrigin.ZERO, false, 0, 1));
            binding.close();
            instance.close();
            dependency.close();
            assertThrows(IllegalStateException.class, binding::retain);
            var retained = captured.inputs().view().spatialMedium();
            assertEquals(30000000.25, retained.originX());
            assertEquals(64.5, retained.originY());
            assertEquals(-30000000.75, retained.originZ());
            assertEquals(dev.comfyfluffy.caustica.api.view.SpatialMedium.Transport.PATH_TRACED, retained.transport());
            assertEquals(0.125f, retained.extinctionMajorant());
            assertNotSame(binding, retained.bindingData());
            assertNotSame(instance, retained.instanceData());
            try (var reader = retained.instanceData().retain()) {
                assertEquals(456, reader.bits());
                assertEquals(123, retained.bindingData().bits());
                captured.close();
                resources.awaitRetirements();
                assertEquals(0, destroyed.get());
            }
            resources.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    @Test void failedSpatialCaptureReleasesPreviouslyRetainedData() {
        var destroyed = new AtomicInteger();
        try (var resources = new dev.comfyfluffy.caustica.engine.resource.ResourceDirectory(failure -> fail(failure))) {
            var dependency = resources.openFactory(new dev.comfyfluffy.caustica.engine.session.ContributionOwner(1))
                    .create(destroyed::incrementAndGet);
            var type = dev.comfyfluffy.caustica.api.program.ShaderDataType.<Object>create("spatial-failure");
            var binding = type.data(123, dependency);
            var instance = type.data(456, dependency);
            var spatial = new dev.comfyfluffy.caustica.api.view.SpatialMedium<>(
                    new dev.comfyfluffy.caustica.api.program.VolumeId<Object, Object>() { }, binding, instance);
            instance.close();
            var view = new SceneView(new SceneId() { }, Camera.IDENTITY,
                    dev.comfyfluffy.caustica.api.view.ViewMedium.Vacuum.INSTANCE, spatial);
            assertThrows(IllegalStateException.class,
                    () -> RtCapturedFrame.capture(new FrameSnapshot(view, SceneOrigin.ZERO, false, 0, 1)));
            binding.close();
            dependency.close();
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
            try (var captured = RtSceneRequest.capture(() -> null, scene::retain,
                    telemetry::publicationCutoff, SceneOrigin.ZERO, 1)) {
                telemetry.afterPublicationVisible(frame -> visible.add("after:" + frame));
                telemetry.frameAssembled(captured.publicationCutoff());
                assertEquals(List.of("before:1"), visible);
            }
            telemetry.endFrame();
            telemetry.beginRenderFrame();
            try (var captured = RtSceneRequest.capture(() -> null, scene::retain,
                    telemetry::publicationCutoff, SceneOrigin.ZERO, 1)) {
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
            @Override public dev.comfyfluffy.caustica.engine.program.ProgramComposition composition() {
                return new dev.comfyfluffy.caustica.engine.program.ProgramComposition(List.of());
            }
            @Override public RtPipeline pipeline() { throw new AssertionError("capture must not record"); }
            @Override public VulkanDeviceAddress compositionDataAddress() { throw new AssertionError(); }
            @Override public void close() { throw new AssertionError("release through shared ownership"); }
        }, ignored -> destroyed.incrementAndGet());
    }
}
