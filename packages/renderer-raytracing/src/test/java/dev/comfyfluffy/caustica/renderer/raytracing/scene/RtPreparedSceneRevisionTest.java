package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RtPreparedSceneRevisionTest {
    private static final ShaderDataType<Object> DATA = ShaderDataType.create("prepared-scene-test");
    private static final SurfaceId<Object, Object> SURFACE = new SurfaceId<>() { };
    private static final ProgramComposition PROGRAMS = new ProgramComposition(List.of(), java.util.Map.of(SURFACE, 1));
    private final SceneId scene = new SceneId() { };

    @Test void workerPreparationReusesTheCompleteRevisionAndOwnsItsSourceUntilEveryReaderFinishes() throws Exception {
        var retired = new AtomicInteger();
        var resolved = new AtomicInteger();
        var resolverThread = new AtomicReference<Thread>();
        var preparation = new RtRetainedSceneBackend.SceneRevisionPreparation((mesh, composition) -> {
            resolved.incrementAndGet();
            resolverThread.set(Thread.currentThread());
            return new RtRetainedSceneBackend.FrameMesh(mesh, null, composition);
        });
        var source = SharedResource.owned(snapshot(1, mesh(), 0, 1), ignored -> retired.incrementAndGet());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> preparation.prepare(source, PROGRAMS)).get(5, TimeUnit.SECONDS);
            var second = worker.submit(() -> preparation.prepare(source, PROGRAMS)).get(5, TimeUnit.SECONDS);
            assertNotSame(Thread.currentThread(), resolverThread.get());
            assertEquals(1, resolved.get());
            assertSame(first.get(), second.get());
            source.close();
            preparation.clear();
            assertEquals(0, retired.get());
            assertEquals(1, first.get().snapshot().revision());
            first.close();
            assertEquals(0, retired.get());
            second.close();
            assertEquals(1, retired.get());
        }
    }

    @Test void replacementKeepsGeometryAndLightsTogetherWithoutChangingAnEarlierReader() {
        var retired = new AtomicInteger();
        var preparation = new RtRetainedSceneBackend.SceneRevisionPreparation(
                (mesh, composition) -> new RtRetainedSceneBackend.FrameMesh(mesh, null, composition));
        var mesh = mesh();
        var firstSource = SharedResource.owned(snapshot(1, mesh, 0, 1), ignored -> retired.incrementAndGet());
        var first = preparation.prepare(firstSource, PROGRAMS);
        firstSource.close();
        var nextSource = SharedResource.owned(snapshot(2, mesh, 8, 2), ignored -> retired.incrementAndGet());
        var next = preparation.prepare(nextSource, PROGRAMS);
        nextSource.close();

        assertEquals(GeometryTransform.translation(0, 0, 0),
                first.get().instances.get(scene).getFirst().instances.getFirst().current().transform());
        assertEquals(1, first.get().content.get(scene).lights().size());
        assertEquals(GeometryTransform.translation(8, 0, 0),
                next.get().instances.get(scene).getFirst().instances.getFirst().current().transform());
        assertEquals(2, next.get().content.get(scene).lights().size());
        assertThrows(UnsupportedOperationException.class, () -> next.get().content.clear());
        assertThrows(UnsupportedOperationException.class, () -> next.get().instances.clear());
        assertEquals(0, retired.get());
        first.close();
        assertEquals(1, retired.get());
        next.close();
        assertEquals(1, retired.get(), "the worker cache retains its source dependencies");
        preparation.clear();
        assertEquals(2, retired.get());
    }

    @Test void preparationFailureReleasesItsBorrowedSourceClaim() {
        var retired = new AtomicInteger();
        var preparation = new RtRetainedSceneBackend.SceneRevisionPreparation((mesh, composition) -> {
            throw new IllegalStateException("mesh resolution failed");
        });
        var source = SharedResource.owned(snapshot(1, mesh(), 0, 0), ignored -> retired.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> preparation.prepare(source, PROGRAMS));
        source.close();
        assertEquals(1, retired.get());
        preparation.clear();
        assertEquals(1, retired.get());
    }

    @Test void failedAssemblyDoesNotReuseBorrowedMeshesAfterReleasingItsSource() {
        var firstMesh = mesh();
        var secondMesh = new RetainedSceneSnapshot.Mesh(2, firstMesh.build(), null);
        var snapshot = new RetainedSceneSnapshot(1,
                List.of(new RetainedSceneSnapshot.Scene(scene, null)),
                List.of(firstMesh, secondMesh), List.of(), List.of());
        var firstResolutions = new AtomicInteger();
        var failures = new AtomicInteger();
        var retired = new AtomicInteger();
        var preparation = new RtRetainedSceneBackend.SceneRevisionPreparation((mesh, composition) -> {
            if (mesh == firstMesh) firstResolutions.incrementAndGet();
            if (mesh == secondMesh && failures.getAndIncrement() == 0) {
                throw new IllegalStateException("second mesh failed");
            }
            return new RtRetainedSceneBackend.FrameMesh(mesh, null, composition);
        });
        try {
            try (var source = SharedResource.owned(snapshot, ignored -> retired.incrementAndGet())) {
                assertThrows(IllegalStateException.class, () -> preparation.prepare(source, PROGRAMS));
            }
            assertEquals(1, retired.get());
            try (var source = SharedResource.owned(snapshot, ignored -> retired.incrementAndGet());
                 var prepared = preparation.prepare(source, PROGRAMS)) {
                assertSame(snapshot, prepared.get().snapshot());
                assertEquals(2, firstResolutions.get());
            }
        } finally {
            preparation.clear();
        }
        assertEquals(2, retired.get());
    }

    @Test void unchangedSourceResolvesAgainstEachCapturedCompositionWithoutChangingEarlierReaders() {
        var preparation = new RtRetainedSceneBackend.SceneRevisionPreparation(
                (mesh, composition) -> new RtRetainedSceneBackend.FrameMesh(mesh, null, composition));
        var absent = new ProgramComposition(List.of());
        try (var source = SharedResource.owned(snapshot(1, mesh(), 0, 1), ignored -> { });
             var beforeCompilation = preparation.prepare(source, absent);
             var afterCompilation = preparation.prepare(source, PROGRAMS);
             var repeated = preparation.prepare(source, PROGRAMS);
             var afterRemoval = preparation.prepare(source, absent)) {
            assertSame(source.get(), beforeCompilation.get().snapshot());
            assertSame(source.get(), afterCompilation.get().snapshot());
            assertSame(afterCompilation.get(), repeated.get());
            assertEquals(0, beforeCompilation.get().instances.get(scene).getFirst().instances
                    .getFirst().geometryRecords().getFirst().surfaceImplementation());
            assertEquals(1, afterCompilation.get().instances.get(scene).getFirst().instances
                    .getFirst().geometryRecords().getFirst().surfaceImplementation());
            assertEquals(0, afterRemoval.get().instances.get(scene).getFirst().instances
                    .getFirst().geometryRecords().getFirst().surfaceImplementation());
            assertSame(beforeCompilation.get().content, afterCompilation.get().content);
            assertSame(afterCompilation.get().content, afterRemoval.get().content);
        } finally {
            preparation.clear();
        }
    }

    private RetainedSceneSnapshot snapshot(long revision, RetainedSceneSnapshot.Mesh mesh, int x, int lightCount) {
        var instance = new RetainedSceneSnapshot.Instance(1, 1, scene, mesh.identity(),
                GeometryTransform.translation(x, 0, 0), 255, DATA.data(0), List.of());
        var lights = new java.util.ArrayList<RetainedSceneSnapshot.Light>();
        for (int i = 0; i < lightCount; i++) {
            lights.add(new RetainedSceneSnapshot.Light(i, scene,
                    new LightDescriptor.Distant(0, 1, 0, i + 1, i + 1, i + 1, 0, false)));
        }
        return new RetainedSceneSnapshot(revision, List.of(new RetainedSceneSnapshot.Scene(scene, null)),
                List.of(mesh), List.of(instance), lights);
    }

    private static RetainedSceneSnapshot.Mesh mesh() {
        var geometry = new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, DATA.data(0),
                new MeshBuild.CoveragePolicy.Opaque()), null, 0, 3);
        var build = new MeshBuild<>(stream(0x1000, 36, 12), stream(0x2000, 12, 4), 3,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, List.of(geometry));
        return new RetainedSceneSnapshot.Mesh(1, build, null);
    }

    private static MeshBuild.Stream stream(long address, int bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes), stride,
                ResourceOwner.none());
    }
}
