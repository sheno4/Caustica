package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelector;

import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtWorkerPool;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.config.CausticaOptions;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import java.nio.file.Path;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFrameAdapterTest {
    @TempDir Path temporaryDirectory;
    private CausticaOptions previousStore;

    @BeforeEach
    void installSettings() {
        previousStore = CausticaConfig.store();
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        CausticaConfig.install(CausticaOptions.load(temporaryDirectory.resolve("caustica.toml"), registry));
    }

    @AfterEach
    void restoreSettings() { CausticaConfig.install(previousStore); }

    @Test
    void epochLeasePublishesAndRemovesOnlyItsOwnSelection() {
        MinecraftFrameAdapter adapter = adapter();
        var binding = ShaderDataType.create("water binding");
        var instance = ShaderDataType.create("water instance");
        SceneId firstScene = new TestScene();
        SceneId secondScene = new TestScene();
        var first = new MinecraftFrameSelector(firstScene, new TestVolume<>(),
                binding.data(1L), instance.data(2L));
        var second = new MinecraftFrameSelector(secondScene, new TestVolume<>(),
                binding.data(3L), instance.data(4L));

        assertNull(adapter.selection(false));
        var firstLease = adapter.installFrameSelector(first);
        assertSame(firstScene, adapter.selection(false).scene());
        var secondLease = adapter.installFrameSelector(second);
        firstLease.close();
        assertSame(secondScene, adapter.selection(false).scene());
        secondLease.close();
        assertNull(adapter.selection(false));
    }

    @Test
    void uiSnapshotDoesNotRetainPriorFrameResources() {
        UiPresentationResources first = MinecraftUiOverlay.snapshotPresentation(
                true, true, image(), 1920, 1080);
        UiPresentationResources second = MinecraftUiOverlay.snapshotPresentation(
                false, false, null, 0, 0);

        assertEquals(11, first.colorImage());
        assertEquals(12, first.colorView());
        assertEquals(1920, first.width());
        assertEquals(1080, first.height());
        assertEquals(UiPresentationResources.EMPTY, second);
    }

    @Test
    void viewTranslationMovesToCameraOriginWithoutChangingClipTransform() {
        Matrix4f baseProjection = new Matrix4f().perspective(1.0f, 1.5f, 0.05f, 1000.0f);
        Matrix4f viewEffect = new Matrix4f()
                .translate(0.08f, -0.12f, 0.0f)
                .rotateZ(0.04f)
                .rotateX(-0.07f);
        Matrix4f levelProjection = new Matrix4f(baseProjection).mul(viewEffect);
        Matrix4f viewRotation = new Matrix4f().rotateY(0.3f);
        double cameraX = 100.0;
        double cameraY = 64.0;
        double cameraZ = -20.0;

        Camera camera = MinecraftFrameAdapter.centerLevelCamera(
                baseProjection, levelProjection, viewRotation, cameraX, cameraY, cameraZ);
        Matrix4f centeredProjection = new Matrix4f().set(camera.clipFromView());
        Vector3f originOffset = new Vector3f(
                (float) (camera.x() - cameraX),
                (float) (camera.y() - cameraY),
                (float) (camera.z() - cameraZ));
        Vector4f originalRelativePoint = new Vector4f(2.0f, -1.0f, -7.0f, 1.0f);
        Vector4f centeredRelativePoint = new Vector4f(
                originalRelativePoint.x - originOffset.x,
                originalRelativePoint.y - originOffset.y,
                originalRelativePoint.z - originOffset.z,
                1.0f);
        Vector4f expectedClip = new Matrix4f(levelProjection).mul(viewRotation)
                .transform(originalRelativePoint, new Vector4f());
        Vector4f actualClip = new Matrix4f(centeredProjection).mul(viewRotation)
                .transform(centeredRelativePoint, new Vector4f());

        assertTrue(expectedClip.equals(actualClip, 1.0e-4f));

        Matrix4f centeredViewProjection = new Matrix4f(centeredProjection).mul(viewRotation);
        Vector4f launchClip = new Vector4f(0.3f, -0.2f, 0.5f, 1.0f);
        Vector4f unprojected = centeredViewProjection.invert(new Matrix4f())
                .transform(launchClip, new Vector4f());
        Vector3f rayDirection = new Vector3f(unprojected.x, unprojected.y, unprojected.z)
                .div(unprojected.w)
                .normalize();
        Vector4f hitClip = centeredViewProjection.transform(
                new Vector4f(rayDirection.mul(10.0f), 1.0f), new Vector4f());

        assertEquals(launchClip.x / launchClip.w, hitClip.x / hitClip.w, 1.0e-5f);
        assertEquals(launchClip.y / launchClip.w, hitClip.y / hitClip.w, 1.0e-5f);
    }

    private static GpuImage image() {
        return new GpuImage() {
            @Override public long image() { return 11; }
            @Override public long view() { return 12; }
            @Override public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) { throw new UnsupportedOperationException(); }
            @Override public int width() { return 1920; }
            @Override public int height() { return 1080; }
            @Override public int format() { return 0; }
        };
    }

    private static MinecraftFrameAdapter adapter() {
        MinecraftTelemetry.Instrumentation instrumentation = MinecraftTelemetry.disabled();
        return new MinecraftFrameAdapter(new RtTerrain(new RtWorkerPool(), instrumentation), instrumentation);
    }

    private static final class TestScene implements SceneId { }
    private static final class TestVolume<B, N> implements VolumeId<B, N> { }
}
