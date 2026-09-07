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

    @Test void unchangedSourceReusesItsTranslationAcrossAnUnrelatedEditAndSceneAddition() throws Exception {
        var mesh = mesh(1, 0x1000, 1);
        var stable = List.of(instance(1, 1, mesh, 0));
        var initial = assembly.resolve(snapshot(List.of(mesh),
                List.of(stable, List.of(instance(2, 200, mesh, 0)))));
        var translation = translation(stable);
        var changed = assembly.resolve(snapshot(List.of(mesh),
                List.of(stable, List.of(instance(2, 200, mesh, 5)))));
        assertSame(translation, translation(stable));
        assertSame(initial.get(scene).getFirst(), changed.get(scene).getFirst());

        var addedScene = new SceneId() {};
        var moreScenes = List.of(new RetainedSceneSnapshot.Scene(scene, null),
                new RetainedSceneSnapshot.Scene(addedScene, null));
        var expanded = assembly.resolve(new RetainedSceneSnapshot(2, moreScenes, List.of(mesh),
                SnapshotList.ofPages(List.of(stable)), List.of()));
        assertSame(translation, translation(stable));
        assertSame(initial.get(scene).getFirst(), expanded.get(scene).getFirst());
        assertTrue(expanded.get(addedScene).isEmpty());
        assertEquals(2, initial.get(scene).size());
        assertEquals(GeometryTransform.translation(0, 0, 0),
                initial.get(scene).getLast().instances.getFirst().current().transform());
    }

    @Test void removingASceneFiltersSharedTranslationWithoutMutatingEarlierFrames() throws Exception {
        var other = new SceneId() {};
        var mesh = mesh(1, 0x1000, 1);
        var source = List.of(instance(1, 1, mesh, 0),
                new RetainedSceneSnapshot.Instance(2, 2, other, mesh.identity(),
                        GeometryTransform.translation(7, 0, 0), 255, DATA.data(0), List.of()));
        var both = List.of(new RetainedSceneSnapshot.Scene(scene, null),
                new RetainedSceneSnapshot.Scene(other, null));
        var initial = assembly.resolve(new RetainedSceneSnapshot(1, both, List.of(mesh),
                SnapshotList.ofPages(List.of(source)), List.of()));
        var originalTranslation = translation(source);
        var removed = assembly.resolve(snapshot(List.of(mesh), List.of(source)));
        var filtered = translation(source);
        assertNotSame(originalTranslation, filtered);
        assertEquals(2, originalTranslation.size());
        assertEquals(1, filtered.size());
        assertFalse(removed.containsKey(other));
        assertSame(initial.get(scene).getFirst(), removed.get(scene).getFirst());
        assertEquals(GeometryTransform.translation(7, 0, 0),
                initial.get(other).getFirst().instances.getFirst().current().transform());
        assembly.resolve(snapshot(List.of(mesh), List.of()));
        assertEquals(2, originalTranslation.size());
        assertEquals(1, initial.get(other).size());
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<SceneId, RtRetainedSceneBackend.InstancePage> translation(
            List<RetainedSceneSnapshot.Instance> source) throws Exception {
        var field = RtRetainedSceneBackend.FrameAssembly.class.getDeclaredField("previousInstancePages");
        field.setAccessible(true);
        var pages = (java.util.Map<List<RetainedSceneSnapshot.Instance>,
                java.util.Map<SceneId, RtRetainedSceneBackend.InstancePage>>) field.get(assembly);
        return pages.get(source);
    }

    @Test void editedPageReusesUnchangedRecordsButBuildsTrueMotionRecords() {
        var mesh = mesh(1, 0x1000, 1);
        var stable = instance(1, 1, mesh, 0);
        var original = snapshot(List.of(mesh), List.of(List.of(stable, instance(2, 2, mesh, 0))));
        var previous = assembly.resolve(original).get(scene).getFirst();
        var current = assembly.resolve(snapshot(List.of(mesh),
                List.of(List.of(stable, instance(2, 2, mesh, 5))))).get(scene).getFirst();

        assertSame(previous.instances.getFirst(), current.instances.getFirst());
        assertSame(previous.stationary.getFirst(), current.stationary.getFirst());
        assertNotSame(previous.instances.getLast().range(), current.instances.getLast().range());
        assertEquals(previous.instances.getLast().range(), current.instances.getLast().range());
        assertSame(previous.stationary.getFirst().geometryRecords(),
                current.stationary.getFirst().geometryRecords());
        assertNotSame(previous.stationary.getLast().geometryRecords(),
                current.stationary.getLast().geometryRecords());
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(List.of(previous),
                SharedResource.owned(original, ignored -> { }))) {
            var motion = current.frame(history);
            assertSame(current.stationary.getFirst(), motion.getFirst());
            assertNotSame(current.stationary.getLast(), motion.getLast());
            assertSame(current.stationary.getFirst().geometryRecords(), motion.getFirst().geometryRecords());
            assertNotSame(current.stationary.getLast().geometryRecords(), motion.getLast().geometryRecords());
            assertEquals(GeometryTransform.translation(0, 0, 0), motion.getLast().previousTransform());
            assertEquals(GeometryTransform.translation(5, 0, 0), motion.getLast().current().transform());
        }
    }

    @Test void sparseOrdinalsMatchHistoryAndReuseRecordsIndependentlyOfIdentityOrder() {
        var mesh = mesh(1, 0x1000, 1);
        var stable = instance(50, 30, mesh, 3);
        var original = snapshot(List.of(mesh), List.of(List.of(
                instance(90, 10, mesh, 1), stable, instance(10, 50, mesh, 5))));
        var previous = assembly.resolve(original).get(scene).getFirst();
        var current = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(
                instance(100, 5, mesh, 7), instance(90, 10, mesh, 11),
                instance(80, 20, mesh, 8), stable, instance(10, 50, mesh, 15),
                instance(5, 60, mesh, 9))))).get(scene).getFirst();

        assertSame(previous.stationary.get(1).geometryRecords(), current.stationary.get(3).geometryRecords());
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(List.of(previous),
                SharedResource.owned(original, ignored -> { }))) {
            var motion = current.frame(history);
            assertSame(current.stationary.get(0), motion.get(0));
            assertSame(current.stationary.get(2), motion.get(2));
            assertSame(current.stationary.get(3), motion.get(3));
            assertSame(current.stationary.get(5), motion.get(5));
            assertEquals(GeometryTransform.translation(1, 0, 0), motion.get(1).previousTransform());
            assertEquals(GeometryTransform.translation(5, 0, 0), motion.get(4).previousTransform());
        }
    }

    @Test void changedPageBoundariesResolveMotionAgainstEachPreviousPage() {
        var mesh = mesh(1, 0x1000, 1);
        var original = snapshot(List.of(mesh), List.of(List.of(instance(20, 10, mesh, 1)),
                List.of(instance(10, 30, mesh, 3))));
        var previous = assembly.resolve(original).get(scene);
        var current = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(
                instance(20, 10, mesh, 11), instance(10, 30, mesh, 13))))).get(scene).getFirst();
        try (var history = new RtRetainedSceneBackend.SceneMotionHistory(previous,
                SharedResource.owned(original, ignored -> { }))) {
            var motion = current.frame(history);
            assertEquals(GeometryTransform.translation(1, 0, 0), motion.getFirst().previousTransform());
            assertEquals(GeometryTransform.translation(3, 0, 0), motion.getLast().previousTransform());
        }
    }

    @Test void tracePlansUseInstanceIdentityAcrossStoragePageReplacement() {
        var mesh = mesh(1, 0x1000, 1);
        var source = snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0))));
        List<RtRetainedSceneBackend.FrameInstanceSnapshot> stationary =
                assembly.resolve(source).get(scene).getFirst().stationary;
        var plans = new RtRetainedSceneBackend.TracePlanCache();

        try (var preparation = new RtFramePreparation()) {
            var first = plans.resolve(List.of(stationary), preparation)[0];
            assertSame(first, plans.resolve(List.of(stationary), preparation)[0]);
            assertSame(first, plans.resolve(List.of(new ArrayList<>(stationary)), preparation)[0]);
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
        assertEquals(1, current.geometryBase());
        assertEquals(RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY, current.sbtRecordOffset());
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
        assertEquals(0, previous.get(other).getFirst().instances.getFirst().geometryBase());
        assertEquals(1, previous.get(scene).getLast().instances.getFirst().geometryBase());
        var next = assembly.resolve(new RetainedSceneSnapshot(2, bothScenes, List.of(mesh),
                SnapshotList.ofPages(List.of(stable)), List.of()));
        assertTrue(next.get(other).isEmpty());
        var rebased = next.get(scene).getFirst();
        assertEquals(1, rebased.instances.getFirst().geometryBase());
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
            assertEquals(0, initial.instances.getFirst().range().geometryBase());
            assertEquals(0, initial.instances.getFirst().range().emitterBase());
            assertEquals(4, initial.instances.getFirst().range().emitterBytes());
            assertEquals(1, changed.getFirst().instances.getFirst().geometryBase());
            var after = cache.resolve(changed.stream().map(page -> page.stationary).toList(), preparation);
            assertSame(before, after[1]);
            assertSame(initial.instances.getFirst().range(), after[1].range);
        }
    }

    @Test void removedNumericRangeCanBeReusedWithoutChangingTheCapturedPage() {
        var mesh = mesh(1, 0x1000, 1);
        var first = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0)))))
                .get(scene).getFirst();
        assembly.resolve(snapshot(List.of(mesh), List.of()));
        var second = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(2, 200, mesh, 5)))))
                .get(scene).getFirst();
        assertEquals(first.instances.getFirst().range(), second.instances.getFirst().range());
        assertNotSame(first.instances.getFirst().range(), second.instances.getFirst().range());
        assertEquals(0, first.stationary.getFirst().current().transform().translationX());
        assertEquals(5, second.stationary.getFirst().current().transform().translationX());
    }

    @Test void singleInstanceEditPreservesTheOther127RangesFramesAndPlans() {
        var mesh = mesh(1, 0x1000, 1);
        var source = new ArrayList<RetainedSceneSnapshot.Instance>();
        for (int i = 0; i < 128; i++) source.add(instance(i + 1, i + 1, mesh, 0));
        var original = snapshot(List.of(mesh), List.of(List.copyOf(source)));
        var before = assembly.resolve(original).get(scene).getFirst();
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        try (var preparation = new RtFramePreparation();
             var history = new RtRetainedSceneBackend.SceneMotionHistory(List.of(before),
                     SharedResource.owned(original, ignored -> { }))) {
            var oldPlans = cache.resolve(List.of(before.stationary), preparation);
            source.set(64, instance(65, 65, mesh, 5));
            var updated = snapshot(List.of(mesh), List.of(List.copyOf(source)));
            var after = assembly.resolve(updated).get(scene).getFirst();
            var motion = after.frame(history);
            var newPlans = cache.resolve(List.of(motion), preparation);
            assertEquals(128, newPlans.length);
            for (int i = 0; i < 128; i++) {
                if (i == 64) continue;
                assertSame(before.instances.get(i), after.instances.get(i));
                assertSame(before.instances.get(i).range(), after.instances.get(i).range());
                assertSame(before.stationary.get(i), motion.get(i));
                assertSame(oldPlans[i], newPlans[i]);
            }
            assertNotSame(oldPlans[64], newPlans[64]);
            assertEquals(before.instances.get(64).range(), after.instances.get(64).range());
            assertNotSame(before.instances.get(64).range(), after.instances.get(64).range());
            assertEquals(GeometryTransform.translation(0, 0, 0), motion.get(64).previousTransform());
            assertEquals(GeometryTransform.translation(5, 0, 0), motion.get(64).current().transform());
            try (var accepted = new RtRetainedSceneBackend.SceneMotionHistory(List.of(after),
                    SharedResource.owned(updated, ignored -> { }))) {
                assertSame(after.stationary, after.frame(accepted));
                assertEquals(GeometryTransform.translation(5, 0, 0), after.frame(accepted).get(64).previousTransform());
            }
            assertSame(newPlans, cache.resolve(List.of(motion), preparation));
        }
    }

    @Test void storagePageSplitAndMergeKeepEverySurvivingInstanceRevision() {
        var mesh = mesh(1, 0x1000, 1);
        var values = List.of(instance(10, 1, mesh, 0), instance(9, 2, mesh, 0),
                instance(8, 3, mesh, 0), instance(7, 4, mesh, 0));
        var first = assembly.resolve(snapshot(List.of(mesh), List.of(values))).get(scene).getFirst();
        var split = assembly.resolve(snapshot(List.of(mesh), List.of(values.subList(0, 2), values.subList(2, 4))))
                .get(scene);
        var merged = assembly.resolve(snapshot(List.of(mesh), List.of(List.copyOf(values)))).get(scene).getFirst();
        for (int i = 0; i < values.size(); i++) {
            assertSame(first.instances.get(i), split.get(i / 2).instances.get(i % 2));
            assertSame(first.instances.get(i), merged.instances.get(i));
            assertSame(first.stationary.get(i), merged.stationary.get(i));
        }
    }

    @Test void growingGeometryRelocatesOnlyItsInstanceAndKeepsCapturedOffsets() {
        var small = mesh(1, 0x1000, 1);
        var large = mesh(2, 0x2000, 1, 2);
        var first = instance(1, 1, small, 0);
        var last = instance(3, 3, small, 0);
        var old = assembly.resolve(snapshot(List.of(small), List.of(List.of(first, instance(2, 2, small, 0), last))))
                .get(scene).getFirst();
        var next = assembly.resolve(snapshot(List.of(small, large), List.of(List.of(first, instance(2, 2, large, 0), last))))
                .get(scene).getFirst();
        assertSame(old.instances.getFirst(), next.instances.getFirst());
        assertSame(old.instances.getLast(), next.instances.getLast());
        assertEquals(1, old.instances.get(1).geometryBase());
        assertEquals(1, old.instances.get(1).range().geometryCount());
        assertEquals(3, next.instances.get(1).geometryBase());
        assertEquals(2, next.instances.get(1).range().geometryCount());
        assertEquals(5, next.geometryHighWater);
    }

    @Test void movingAnInstanceToAnotherSceneReleasesOnlyItsOldAllocation() {
        var other = new SceneId() { };
        var mesh = mesh(1, 0x1000, 1);
        var stable = instance(2, 2, mesh, 0);
        var both = List.of(new RetainedSceneSnapshot.Scene(scene, null), new RetainedSceneSnapshot.Scene(other, null));
        var old = assembly.resolve(new RetainedSceneSnapshot(1, both, List.of(mesh),
                SnapshotList.ofPages(List.of(List.of(instance(1, 1, mesh, 0), stable))), List.of()));
        var moved = new RetainedSceneSnapshot.Instance(1, 1, other, mesh.identity(),
                GeometryTransform.translation(0, 0, 0), 255, DATA.data(0), List.of());
        var next = assembly.resolve(new RetainedSceneSnapshot(2, both, List.of(mesh),
                SnapshotList.ofPages(List.of(List.of(moved, stable))), List.of()));
        assertSame(old.get(scene).getFirst().instances.getLast(), next.get(scene).getFirst().instances.getFirst());
        assertEquals(0, next.get(other).getFirst().instances.getFirst().geometryBase());
        assertNotSame(old.get(scene).getFirst().instances.getFirst().range(),
                next.get(other).getFirst().instances.getFirst().range());
    }

    @Test void batchRetentionKeepsSkippedInstancesAndPrunesOnlyReplacedRanges() {
        var mesh = mesh(1, 0x1000, 1);
        var stable = List.of(instance(2, 200, mesh, 0));
        var first = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0)), stable)))
                .get(scene);
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        var slot = new RtRetainedSceneBackend.TraceSlot(null, null, null, null, null);
        try (var preparation = new RtFramePreparation()) {
            var initial = cache.resolveBatches(first.stream().map(page -> page.stationary).toList(), preparation);
            var firstStamp = slot.batch(initial[0], initial);
            var secondStamp = slot.batch(initial[1], initial);
            var firstRange = initial[0].plans[0].range;
            var secondRange = initial[1].plans[0].range;
            var firstResidency = slot.page(firstRange, firstStamp);
            var secondResidency = slot.page(secondRange, secondStamp);
            slot.retainPages(initial);
            assertSame(initial, cache.resolveBatches(first.stream().map(page -> page.stationary).toList(), preparation));

            var next = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 5)), stable)))
                    .get(scene);
            var changed = cache.resolveBatches(next.stream().map(page -> page.stationary).toList(), preparation);
            assertSame(initial[1], changed[1]);
            assertNotSame(initial[0], changed[0]);
            var replacementRange = changed[0].plans[0].range;
            assertEquals(firstRange, replacementRange);
            assertNotSame(firstRange, replacementRange);
            var replacementStamp = slot.batch(changed[0], changed);
            assertSame(secondStamp, slot.batch(changed[1], changed));
            var replacementResidency = slot.page(replacementRange, replacementStamp);
            slot.retainPages(changed);
            assertEquals(2, slot.pages.size());
            assertEquals(2, slot.batches.size());
            assertFalse(slot.pages.containsKey(firstRange));
            assertNotSame(firstResidency, replacementResidency);
            assertSame(secondResidency, slot.pages.get(secondRange));
            slot.retainPages(changed);
            assertEquals(2, slot.pages.size());

            var regrouped = cache.resolveBatches(List.of(List.of(next.getFirst().stationary.getFirst(),
                    next.getLast().stationary.getFirst())), preparation);
            var regroupedStamp = slot.batch(regrouped[0], regrouped);
            assertSame(replacementResidency, slot.page(replacementRange, regroupedStamp));
            assertSame(secondResidency, slot.page(secondRange, regroupedStamp));
            slot.retainPages(regrouped);
            assertEquals(2, slot.pages.size());
            assertEquals(1, slot.batches.size());

            slot.retainPages(new RtRetainedSceneBackend.TraceBatch[0]);
            assertTrue(slot.pages.isEmpty());
            assertTrue(slot.batches.isEmpty());
        }
    }

    @Test void batchResolutionSharesUnchangedArraysAndFlattensOnlyTheRequestedRevision() {
        var mesh = mesh(1, 0x1000, 1);
        var stable = List.of(instance(2, 200, mesh, 0));
        var first = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0)), stable)))
                .get(scene);
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        try (var preparation = new RtFramePreparation()) {
            var initialPages = first.stream().map(page -> page.stationary).toList();
            var initial = cache.resolveBatches(initialPages, preparation);
            var initialFlat = cache.resolve(initialPages, preparation);
            var next = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 5)), stable)))
                    .get(scene);
            var nextPages = next.stream().map(page -> page.stationary).toList();
            var updated = cache.resolveBatches(nextPages, preparation);
            assertSame(initial[1], updated[1]);
            assertSame(initial[1].plans, updated[1].plans);
            assertNotSame(initial[0], updated[0]);
            assertSame(updated, cache.resolveBatches(nextPages, preparation));
            var updatedFlat = cache.resolve(nextPages, preparation);
            assertSame(updated[0].plans[0], updatedFlat[0]);
            assertSame(initialFlat[1], updatedFlat[1]);
            assertEquals(GeometryTransform.translation(0, 0, 0), initialFlat[0].identity.current().transform());
            assertEquals(GeometryTransform.translation(5, 0, 0), updatedFlat[0].identity.current().transform());
            assertSame(updatedFlat, cache.resolve(nextPages, preparation));
            assertEquals(0, cache.resolveBatches(List.of(), preparation).length);
            assertEquals(0, cache.resolve(List.of(), preparation).length);
            assertSame(updated[1], cache.resolveBatches(nextPages, preparation)[1]);
        }
    }

    @Test void traceBatchesCountGeometryAndSelectOnlyEmittingInstances() {
        var mesh = mesh(1, 0x1000, 1);
        var emitter = new RetainedSceneSnapshot.Instance(2, 2, scene, mesh.identity(),
                GeometryTransform.translation(0, 0, 0), 255, DATA.data(0),
                List.of(new RetainedSceneSnapshot.PrimitiveEmitter(0, 1, 42)));
        var page = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0), emitter))))
                .get(scene).getFirst();
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        try (var preparation = new RtFramePreparation()) {
            var batch = cache.resolveBatches(List.of(page.stationary), preparation)[0];
            assertEquals(2, batch.geometryCount);
            assertEquals(4, batch.emitterBytes);
            assertEquals(1, batch.emitters.length);
            assertSame(batch.plans[1], batch.emitters[0]);
            var slot = new RtRetainedSceneBackend.TraceSlot(null, null, null, null, null);
            var batches = new RtRetainedSceneBackend.TraceBatch[] {batch};
            var stamp = slot.batch(batch, batches);
            slot.page(batch.plans[0].range, stamp);
            var emitterResidency = slot.page(batch.plans[1].range, stamp);
            emitterResidency.linkedEmittersWritten(new int[] {42});
            var layout = cache.emitterLayout();
            Object lights = new Object();
            var linked = slot.linked(layout, lights);
            assertTrue(linked.get(42));
            assertSame(linked, slot.linked(layout, lights));
            emitterResidency.linkedEmittersWritten(new int[] {9});
            var reindexed = slot.linked(layout, new Object());
            assertTrue(reindexed.get(9));
            assertFalse(reindexed.get(42));
            assertTrue(linked.get(42));
        }
    }

    @Test void nonEmittingEditsAndStorageRegroupingKeepEmitterLayoutAndLinkedLights() {
        var mesh = mesh(1, 0x1000, 1);
        var emitter = new RetainedSceneSnapshot.Instance(2, 2, scene, mesh.identity(),
                GeometryTransform.translation(0, 0, 0), 255, DATA.data(0),
                List.of(new RetainedSceneSnapshot.PrimitiveEmitter(0, 1, 42)));
        var first = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 0), emitter))))
                .get(scene).getFirst();
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        var slot = new RtRetainedSceneBackend.TraceSlot(null, null, null, null, null);
        Object lights = new Object();
        try (var preparation = new RtFramePreparation()) {
            var batches = cache.resolveBatches(List.of(first.stationary), preparation);
            var layout = cache.emitterLayout();
            var stamp = slot.batch(batches[0], batches);
            slot.page(batches[0].emitters[0].range, stamp).linkedEmittersWritten(new int[] {7});
            var linked = slot.linked(layout, lights);
            var next = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(instance(1, 1, mesh, 5), emitter))))
                    .get(scene).getFirst();
            cache.resolveBatches(List.of(next.stationary), preparation);
            assertSame(layout, cache.emitterLayout());
            assertSame(linked, slot.linked(cache.emitterLayout(), lights));
            cache.resolveBatches(List.of(List.of(next.stationary.getFirst()), List.of(next.stationary.getLast())), preparation);
            assertSame(layout, cache.emitterLayout());
            assertSame(linked, slot.linked(cache.emitterLayout(), lights));
            cache.resolveBatches(List.of(List.of(next.stationary.getFirst())), preparation);
            assertNotSame(layout, cache.emitterLayout());
            assertTrue(slot.linked(cache.emitterLayout(), lights).isEmpty());
            assertTrue(linked.get(7));
        }
    }

    @Test void motionResolvedEmitterPlansKeepTheCurrentEmitterLayout() {
        var mesh = mesh(1, 0x1000, 1);
        var originalEmitter = new RetainedSceneSnapshot.Instance(1, 1, scene, mesh.identity(),
                GeometryTransform.translation(0, 0, 0), 255, DATA.data(0),
                List.of(new RetainedSceneSnapshot.PrimitiveEmitter(0, 1, 42)));
        var old = snapshot(List.of(mesh), List.of(List.of(originalEmitter)));
        var before = assembly.resolve(old).get(scene);
        var movedEmitter = new RetainedSceneSnapshot.Instance(1, 1, scene, mesh.identity(),
                GeometryTransform.translation(5, 0, 0), 255, DATA.data(0), originalEmitter.primitiveEmitters());
        var current = assembly.resolve(snapshot(List.of(mesh), List.of(List.of(movedEmitter)))).get(scene).getFirst();
        var cache = new RtRetainedSceneBackend.TracePlanCache();
        try (var preparation = new RtFramePreparation();
             var history = new RtRetainedSceneBackend.SceneMotionHistory(before, SharedResource.owned(old, ignored -> { }))) {
            cache.resolveBatches(List.of(before.getFirst().stationary), preparation);
            var oldLayout = cache.emitterLayout();
            var stationary = cache.resolveBatches(List.of(current.stationary), preparation);
            var currentLayout = cache.emitterLayout();
            assertNotSame(oldLayout, currentLayout);
            var motion = cache.resolveBatches(List.of(current.frame(history)), preparation);
            assertNotSame(stationary[0], motion[0]);
            assertSame(currentLayout, cache.emitterLayout());
            cache.resolveBatches(List.of(current.stationary), preparation);
            assertSame(currentLayout, cache.emitterLayout());
        }
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
        return mesh(id, positions, program, 1);
    }

    private static RetainedSceneSnapshot.Mesh mesh(long id, long positions, int program, int geometryCount) {
        var geometries = new ArrayList<MeshBuild.Geometry<Object>>();
        var programs = new ArrayList<RetainedSceneSnapshot.GeometryPrograms>();
        for (int i = 0; i < geometryCount; i++) {
            geometries.add(new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, DATA.data(0),
                    new MeshBuild.CoveragePolicy.Opaque()), null, i * 3, 3));
            programs.add(new RetainedSceneSnapshot.GeometryPrograms(program, 0));
        }
        var build = new MeshBuild<>(stream(positions, 36, 12), stream(0x8000, geometryCount * 12, 4), 3,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, geometries);
        return new RetainedSceneSnapshot.Mesh(id, build, programs, null);
    }

    private static MeshBuild.Stream stream(long address, int bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes), stride,
                ResourceOwner.none());
    }
}
