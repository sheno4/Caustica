package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RtFramePageAssemblyTest {
    private static final ShaderDataType<Object> DATA = ShaderDataType.create("page-test");
    private static final SurfaceId<Object, Object> SURFACE = new SurfaceId<>() { };
    private final SceneId scene = new SceneId() { };
    private final List<RetainedSceneSnapshot.Scene> scenes = List.of(new RetainedSceneSnapshot.Scene(scene, null));
    private final RtRetainedSceneBackend.FrameAssembly assembly = new RtRetainedSceneBackend.FrameAssembly(
            mesh -> new RtRetainedSceneBackend.FrameMesh(mesh, null));

    @Test void unchangedPagesReuseInstancesRecordsAndHistoryWithoutFlattening() {
        var mesh = mesh(1, 0x1000, 1);
        var firstPage = List.of(instance(1, 1, mesh, 0));
        var secondPage = List.of(instance(2, 200, mesh, 0));
        var original = snapshot(List.of(mesh), List.of(firstPage, secondPage));
        var first = assembly.resolve(original);
        assertSame(first, assembly.resolve(original));
        var next = assembly.resolve(snapshot(List.of(mesh), List.of(firstPage,
                List.of(instance(2, 200, mesh, 5)))));
        assertSame(first.get(scene).getFirst(), next.get(scene).getFirst());
        assertNotSame(first.get(scene).getLast(), next.get(scene).getLast());
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(first.get(scene),
                SharedResource.owned(original, ignored -> { }))) {
            var stable = next.get(scene).getFirst();
            assertSame(stable.stationary, stable.frame(history));
            assertSame(stable.stationary.getFirst().geometryRecords(), stable.frame(history).getFirst().geometryRecords());
            var moving = next.get(scene).getLast().frame(history).getFirst();
            assertEquals(GeometryTransform.translation(0, 0, 0), moving.previousTransform());
            assertEquals(GeometryTransform.translation(5, 0, 0), moving.current().transform());
        }
    }

    @Test void editedPageReusesUnchangedRecordsButBuildsTrueMotionRecords() {
        var mesh = mesh(1, 0x1000, 1);
        var stable = instance(1, 1, mesh, 0);
        var original = snapshot(List.of(mesh), List.of(List.of(stable, instance(2, 2, mesh, 0))));
        var previous = assembly.resolve(original).get(scene).getFirst();
        var current = assembly.resolve(snapshot(List.of(mesh),
                List.of(List.of(stable, instance(2, 2, mesh, 5))))).get(scene).getFirst();

        assertNotSame(previous.range, current.range);
        assertSame(previous.stationary.getFirst().geometryRecords(),
                current.stationary.getFirst().geometryRecords());
        assertNotSame(previous.stationary.getLast().geometryRecords(),
                current.stationary.getLast().geometryRecords());
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(List.of(previous),
                SharedResource.owned(original, ignored -> { }))) {
            var motion = current.frame(history);
            assertSame(current.stationary.getFirst().geometryRecords(), motion.getFirst().geometryRecords());
            assertNotSame(current.stationary.getLast().geometryRecords(), motion.getLast().geometryRecords());
            assertEquals(GeometryTransform.translation(0, 0, 0), motion.getLast().previousTransform());
            assertEquals(GeometryTransform.translation(5, 0, 0), motion.getLast().current().transform());
        }
    }

    @Test void tracePlansUseMotionResolvedPageIdentity() {
        var mesh = mesh(1, 0x1000, 1);
        var source = snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0))));
        List<RtRetainedSceneBackend.FrameInstanceSnapshot> stationary =
                assembly.resolve(source).get(scene).getFirst().stationary;
        var plans = new RtRetainedSceneBackend.TracePlanCache();

        try (var preparation = new RtFramePreparation()) {
            var first = plans.resolve(List.of(stationary), preparation)[0];
            assertSame(first, plans.resolve(List.of(stationary), preparation)[0]);
            assertNotSame(first, plans.resolve(List.of(new ArrayList<>(stationary)), preparation)[0]);
        }
    }

    @Test void changedMeshPagesKeepUnchangedMeshesAndProgramChangesInvalidateResolution() {
        var firstMesh = mesh(1, 0x1000, 1);
        var oldMesh = mesh(2, 0x2000, 1);
        var newMesh = mesh(3, 0x3000, 1);
        var stable = List.of(instance(1, 1, firstMesh, 0));
        var first = assembly.resolve(snapshot(List.of(firstMesh, oldMesh),
                List.of(stable, List.of(instance(2, 200, oldMesh, 0)))));
        var second = assembly.resolve(snapshot(List.of(firstMesh, newMesh),
                List.of(stable, List.of(instance(2, 200, newMesh, 0)))));
        assertSame(first.get(scene).getFirst(), second.get(scene).getFirst());
        var republished = new RetainedSceneSnapshot.Mesh(1, firstMesh.build(),
                List.of(new RetainedSceneSnapshot.GeometryPrograms(7, 0)), null);
        var third = assembly.resolve(snapshot(List.of(republished, newMesh),
                List.of(stable, List.of(instance(2, 200, newMesh, 0)))));
        assertNotSame(second.get(scene).getFirst(), third.get(scene).getFirst());
        assertNotSame(second.get(scene).getFirst().stationary.getFirst().geometryRecords(),
                third.get(scene).getFirst().stationary.getFirst().geometryRecords());
        assertEquals(7, third.get(scene).getFirst().stationary.getFirst().geometryRecords().getFirst().surfaceImplementation());
        assertEquals(1, first.get(scene).getFirst().stationary.getFirst().geometryRecords().getFirst().surfaceImplementation());
    }

    @Test void replacedPagePreservesPreviousMeshStreamAfterEarlierPageRemoval() {
        var oldMesh = mesh(1, 0x1000, 1);
        var newMesh = mesh(2, 0x1000, 1);
        var first = snapshot(List.of(oldMesh), List.of(List.of(instance(1, 1, oldMesh, 0)),
                List.of(instance(2, 200, oldMesh, 0))));
        var pages = assembly.resolve(first).get(scene);
        assertEquals(1, pages.getLast().stationary.getFirst().geometryBase());
        var release = new AtomicInteger();
        var root = SharedResource.owned(first, ignored -> release.incrementAndGet());
        var history = new RtRetainedSceneBackend.SceneMotionHistory(pages, root.retain());
        root.close();
        var currentPage = assembly.resolve(snapshot(List.of(newMesh),
                List.of(List.of(instance(2, 200, newMesh, 5))))).get(scene).getFirst();
        var current = currentPage.frame(history).getFirst();
        assertEquals(0, current.geometryBase());
        assertEquals(0, current.sbtRecordOffset());
        assertSame(oldMesh.build().positions(), current.previousPositions());
        assertNotSame(newMesh.build().positions(), current.previousPositions());
        assertNotSame(currentPage.stationary.getFirst().geometryRecords(), current.geometryRecords());
        assertEquals(0, release.get());
        history.close();
        assertEquals(1, release.get());
    }

    @Test void removedAndReinsertedIdentityStartsWithoutPreviousMotion() {
        var mesh = mesh(1, 0x1000, 1);
        var old = snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0))));
        var pages = assembly.resolve(old).get(scene);
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(pages, SharedResource.owned(old, ignored -> { }))) {
            var current = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 200, mesh, 5)))))
                    .get(scene).getFirst().frame(history).getFirst();
            assertEquals(current.current().transform(), current.previousTransform());
        }
    }

    @Test void sceneRangesAreIndependentAndRemovalKeepsStationaryGeometryRecords() {
        var other = new SceneId() { };
        var mesh = mesh(1, 0x1000, 1);
        var source = List.of(instance(1, 1, mesh, 0),
                new RetainedSceneSnapshot.Instance(2, 2, other, 1,
                        GeometryTransform.translation(0, 0, 0), 255, DATA.data(0), List.of()));
        var stable = List.of(instance(3, 200, mesh, 0));
        var bothScenes = List.of(new RetainedSceneSnapshot.Scene(scene, null), new RetainedSceneSnapshot.Scene(other, null));
        var old = new RetainedSceneSnapshot(1, bothScenes, List.of(mesh),
                SnapshotList.ofPages(List.of(source, stable)), List.of());
        var previous = assembly.resolve(old);
        assertEquals(0, previous.get(other).getFirst().geometryBase);
        assertEquals(1, previous.get(scene).getLast().geometryBase);
        var next = assembly.resolve(new RetainedSceneSnapshot(2, bothScenes, List.of(mesh),
                SnapshotList.ofPages(List.of(stable)), List.of()));
        assertTrue(next.get(other).isEmpty());
        var rebased = next.get(scene).getFirst();
        assertEquals(1, rebased.geometryBase);
        assertSame(previous.get(scene).getLast(), rebased);
        assertSame(previous.get(scene).getLast().stationary.getFirst().geometryRecords(),
                rebased.stationary.getFirst().geometryRecords());
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(previous.get(scene),
                SharedResource.owned(old, ignored -> { }))) {
            assertSame(rebased.stationary, rebased.frame(history));
        }
    }

    @Test void insertingAnEarlierPageKeepsGeometryAndEmitterOffsetsAndCachedPlan() {
        var mesh = mesh(1, 0x1000, 1);
        var emitter = new RetainedSceneSnapshot.Instance(2, 200, scene, mesh.identity(),
                GeometryTransform.translation(0, 0, 0), 255, DATA.data(0),
                List.of(new RetainedSceneSnapshot.PrimitiveEmitter(0, 1, 42)));
        var stable = List.of(emitter);
        var initial = assembly.resolve(snapshot(List.of(mesh), List.of(stable))).get(scene).getFirst();
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        try (var preparation = new RtFramePreparation()) {
            var before = cache.resolve(List.of(initial.stationary), preparation)[0];
            var changed = assembly.resolve(snapshot(List.of(mesh),
                    List.of(List.of(instance(1, 1, mesh, 0)), stable))).get(scene);
            assertSame(initial, changed.getLast());
            assertEquals(0, initial.range.geometryBase());
            assertEquals(0, initial.range.emitterBase());
            assertEquals(4, initial.range.emitterBytes());
            assertEquals(1, changed.getFirst().geometryBase);
            var after = cache.resolve(changed.stream().map(page -> page.stationary).toList(), preparation);
            assertSame(before, after[1]);
            assertSame(initial.range, after[1].range);
        }
    }

    @Test void removedNumericRangeCanBeReusedWithoutChangingTheCapturedPage() {
        var mesh = mesh(1, 0x1000, 1);
        var first = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0)))))
                .get(scene).getFirst();
        assembly.resolve(snapshot(List.of(mesh), List.of()));
        var second = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(2, 200, mesh, 5)))))
                .get(scene).getFirst();
        assertEquals(first.range, second.range);
        assertNotSame(first.range, second.range);
        assertEquals(0, first.stationary.getFirst().current().transform().translationX());
        assertEquals(5, second.stationary.getFirst().current().transform().translationX());
    }

    private RetainedSceneSnapshot snapshot(List<RetainedSceneSnapshot.Mesh> meshes,
                                            List<List<RetainedSceneSnapshot.Instance>> pages) {
        return new RetainedSceneSnapshot(1, scenes, SnapshotList.ofPages(List.of(meshes)),
                SnapshotList.ofPages(pages), List.of());
    }

    private RetainedSceneSnapshot.Instance instance(long id, long ordinal, RetainedSceneSnapshot.Mesh mesh, int x) {
        return new RetainedSceneSnapshot.Instance(id, ordinal, scene, mesh.identity(),
                GeometryTransform.translation(x, 0, 0), 255, DATA.data(0), List.of());
    }

    private static RetainedSceneSnapshot.Mesh mesh(long id, long positions, int program) {
        var geometry = new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, DATA.data(0),
                new MeshBuild.CoveragePolicy.Opaque()), null, 0, 3);
        var build = new MeshBuild<>(stream(positions, 36, 12), stream(0x8000, 12, 4), 3,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, List.of(geometry));
        return new RetainedSceneSnapshot.Mesh(id, build,
                List.of(new RetainedSceneSnapshot.GeometryPrograms(program, 0)), null);
    }

    private static MeshBuild.Stream stream(long address, int bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes), stride,
                ResourceOwner.none());
    }
}
