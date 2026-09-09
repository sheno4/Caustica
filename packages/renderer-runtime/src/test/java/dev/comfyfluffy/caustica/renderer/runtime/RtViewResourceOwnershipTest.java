package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RtViewResourceOwnershipTest {
    @Test
    void captureRetainsCameraMediumBeforeAnyCommandsAreRecorded() {
        AtomicInteger destroyed = new AtomicInteger();
        try (var resources = new ResourceDirectory(failure -> fail(failure))) {
            var factory = resources.openFactory(new ContributionOwner(1));
            var binding = factory.create(destroyed::incrementAndGet);
            var instance = factory.create(destroyed::incrementAndGet);
            ShaderDataType<Object> type = ShaderDataType.create("camera medium");
            var medium = new ViewMedium.Volume<>(new VolumeId<Object, Object>() { },
                    type.data(0x1000, binding), type.data(0x2000, instance));
            var captured = RtCapturedFrame.capture(inputs(medium));
            medium.bindingData().close();
            medium.instanceData().close();

            binding.close();
            instance.close();
            resources.awaitRetirements();
            assertEquals(0, destroyed.get());

            captured.close();
            resources.awaitRetirements();
            assertEquals(2, destroyed.get());
        }
    }

    @Test
    void failedCaptureReleasesEarlierAcquisitions() {
        AtomicInteger destroyed = new AtomicInteger();
        try (var resources = new ResourceDirectory(failure -> fail(failure))) {
            var factory = resources.openFactory(new ContributionOwner(1));
            var owner = factory.create(destroyed::incrementAndGet);
            var released = factory.create();
            var reference = released;

            ShaderDataType<Object> type = ShaderDataType.create("camera medium");
            var medium = new ViewMedium.Volume<>(new VolumeId<Object, Object>() { },
                    type.data(0x1000, owner), type.data(0x2000, reference));
            medium.instanceData().close();
            released.close();
            assertThrows(IllegalStateException.class, () -> RtCapturedFrame.capture(inputs(medium)));
            medium.bindingData().close();
            owner.close();
            resources.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }

    private static FrameSnapshot inputs(ViewMedium medium) {
        return new FrameSnapshot(new SceneView(new SceneId() { }, Camera.IDENTITY, medium),
                SceneOrigin.ZERO, false, 0, 1);
    }
}
