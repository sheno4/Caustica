package dev.comfyfluffy.caustica.rt.pass;


import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuFrameUse;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.pass.PassKey;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtPassSchedulerBackendTest {
    @Test
    void acquiredOutputIsStableAndAdvancesValidatedChain() {
        Fixture fixture = new Fixture();
        PassSchedulerBackend.PostInvocation first = fixture.backend.beginPostEffect(post(0));

        assertSame(fixture.reconstruction, first.frame().sceneColor());
        GpuImage firstOutput = first.frame().acquireSceneColorOutput();
        assertSame(firstOutput, first.frame().acquireSceneColorOutput());
        assertNotSame(first.frame().sceneColor(), firstOutput);
        assertThrows(IllegalStateException.class, () -> first.submit(() -> { }));

        first.validateOutputChain();
        first.submit(() -> fixture.firstDrained.set(true));
        assertSame(firstOutput, fixture.backend.sceneColor());
        assertEquals(1, fixture.commands.barriers);
        assertEquals(1, fixture.use.pending.size());
        assertTrue(!fixture.firstDrained.get());

        PassSchedulerBackend.PostInvocation second = fixture.backend.beginPostEffect(post(1));
        assertSame(firstOutput, second.frame().sceneColor());
        assertNotSame(firstOutput, second.frame().acquireSceneColorOutput());
        second.validateOutputChain();
        second.submit(() -> { });
        assertEquals(2, fixture.commands.binds);
    }

    @Test
    void abandonedAcquisitionDoesNotPublishItsOutput() {
        Fixture fixture = new Fixture();
        PassSchedulerBackend.PostInvocation invocation = fixture.backend.beginPostEffect(post(0));
        var borrowed = invocation.frame();
        borrowed.acquireSceneColorOutput();
        invocation.validateOutputChain();
        assertNotSame(fixture.reconstruction, fixture.backend.sceneColor());

        AtomicBoolean drained = new AtomicBoolean();
        invocation.abandon(new IllegalStateException("record failed"), () -> drained.set(true));

        assertSame(fixture.reconstruction, fixture.backend.sceneColor());
        assertThrows(IllegalStateException.class, borrowed::sceneColor);
        assertEquals(1, fixture.commands.barriers);
        assertTrue(!drained.get());
        fixture.use.drainOne();
        assertTrue(drained.get());
    }

    @Test
    void submittedInvocationRetiresOnlyThroughFrameReservation() {
        Fixture fixture = new Fixture();
        var invocation = fixture.backend.beginWorldResource(
                new PassKey(0, PassKey.Stage.WORLD_RESOURCE));
        AtomicBoolean drained = new AtomicBoolean();

        assertSame(fixture.view, invocation.frame().view());
        assertEquals(12.5, invocation.frame().timeSeconds());
        assertEquals(0.5, invocation.frame().metersPerSceneUnit());
        assertEquals(960, invocation.frame().renderWidth());
        assertEquals(540, invocation.frame().renderHeight());

        invocation.submit(() -> drained.set(true));

        assertTrue(!drained.get());
        assertEquals(1, fixture.use.pending.size());
        fixture.use.drainOne();
        assertTrue(drained.get());
        assertThrows(IllegalStateException.class, invocation::frame);
    }

    @Test
    void frameMustEndWithoutAnActiveInvocationAndCannotBeReadAfterward() {
        Fixture fixture = new Fixture();
        var invocation = fixture.backend.beginWorldResource(
                new PassKey(0, PassKey.Stage.WORLD_RESOURCE));

        assertThrows(IllegalStateException.class, fixture.backend::endFrame);
        assertThrows(IllegalStateException.class, () -> fixture.backend.beginFrame(fixture.frameState()));
        invocation.submit(() -> { });
        assertThrows(IllegalStateException.class, () -> fixture.backend.beginFrame(fixture.frameState()));
        fixture.backend.endFrame();

        assertThrows(IllegalStateException.class, fixture.backend::sceneColor);
        fixture.backend.beginFrame(fixture.frameState());
        assertSame(fixture.reconstruction, fixture.backend.sceneColor());
    }

    private static PassKey post(long sequence) {
        return new PassKey(sequence, PassKey.Stage.POST_EFFECT);
    }

    private static final class Fixture {
        final FakeUse use = new FakeUse();
        final FakeCommands commands = new FakeCommands();
        final FakeImage reconstruction = new FakeImage(1);
        final FakeImage exposure = new FakeImage(2);
        final FakeImage postA = new FakeImage(3);
        final FakeImage postB = new FakeImage(4);
        final SceneView view = new SceneView(new SceneId() { }, Camera.IDENTITY);
        final RtPassSchedulerBackend backend = new RtPassSchedulerBackend(
                new FakeGpu(), 97, 100, 37, commands);
        final AtomicBoolean firstDrained = new AtomicBoolean();

        Fixture() {
            backend.beginFrame(frameState());
        }

        RtPassSchedulerBackend.FrameState frameState() {
            return new RtPassSchedulerBackend.FrameState(
                    fakeCommandBuffer(), use, 4L, view, 12.5, 0.5, 960, 540,
                    reconstruction, exposure, postA, postB, null);
        }
    }

    private static final class FakeUse implements GpuFrameUse {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void whenSubmitted(Runnable callback) { callback.run(); }
        @Override public void whenComplete(Runnable callback) { pending.add(callback); }
        void drainOne() { pending.remove().run(); }
    }

    private static final class FakeCommands implements RtPassSchedulerBackend.CommandHooks {
        int binds;
        int barriers;
        @Override public void bindDescriptorHeaps(VkCommandBuffer commandBuffer) { binds++; }
        @Override public void passBarrier(VkCommandBuffer commandBuffer) { barriers++; }
    }

    private static final class FakeGpu implements GpuDevice {
        @Override public VkDevice vk() { return null; }
        @Override public long vmaAllocator() { return 0L; }
        @Override public GpuDescriptorHeap descriptorHeap() { return null; }
        @Override public void retireAfterUse(Runnable cleanup) { throw new AssertionError(); }
    }

    private record FakeImage(long image) implements GpuImage {
        @Override public long view() { return image; }
        @Override public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) { return null; }
        @Override public int width() { return 1920; }
        @Override public int height() { return 1080; }
        @Override public int format() { return 97; }
    }

    private static VkCommandBuffer fakeCommandBuffer() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (VkCommandBuffer) ((Unsafe) field.get(null)).allocateInstance(VkCommandBuffer.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
