package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RtViewResourceOwnershipTest {
    @Test
    void frameRetainsBothCameraMediumDataGraphsUntilCompletion() {
        AtomicInteger destroyed = new AtomicInteger();
        try (var resources = new ResourceDirectory(failure -> fail(failure))) {
            var factory = resources.openFactory(new ContributionOwner(1));
            var binding = factory.create(destroyed::incrementAndGet);
            var instance = factory.create(destroyed::incrementAndGet);
            ShaderDataType<Object> type = ShaderDataType.create("camera medium");
            var medium = new ViewMedium.Volume<>(new VolumeId<Object, Object>() { },
                    type.data(0x1000, binding.reference()), type.data(0x2000, instance.reference()));
            var callbacks = new ArrayList<Runnable>();
            RtFrameRenderer.retainViewResources(medium, new GpuFrameUse() {
                @Override public void whenSubmitted(Runnable callback) { fail("ownership requires no submission callback"); }
                @Override public void whenComplete(Runnable callback) { callbacks.add(callback); }
            });

            binding.close();
            instance.close();
            resources.awaitRetirements();
            assertEquals(0, destroyed.get());

            callbacks.forEach(Runnable::run);
            resources.awaitRetirements();
            assertEquals(2, destroyed.get());
        }
    }

    @Test
    void rejectedCompletionRegistrationReleasesItsAcquiredOwners() {
        AtomicInteger destroyed = new AtomicInteger();
        try (var resources = new ResourceDirectory(failure -> fail(failure))) {
            var owner = resources.openFactory(new ContributionOwner(1)).create(destroyed::incrementAndGet);
            ShaderDataType<Object> type = ShaderDataType.create("camera medium");
            var medium = new ViewMedium.Volume<>(new VolumeId<Object, Object>() { },
                    type.data(0x1000, owner.reference()), type.data(0x2000, owner.reference()));
            assertThrows(IllegalStateException.class, () -> RtFrameRenderer.retainViewResources(medium,
                    new GpuFrameUse() {
                        @Override public void whenSubmitted(Runnable callback) { fail(); }
                        @Override public void whenComplete(Runnable callback) { throw new IllegalStateException("resolved"); }
                    }));
            owner.close();
            resources.awaitRetirements();
            assertEquals(1, destroyed.get());
        }
    }
}
