package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;
import dev.comfyfluffy.caustica.minecraft.rendering.PreparedScene;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftEntityGeometryTest {
    @Test void abandonedCaptureCleanupFailureIsReportedByTheNextSubmission() {
        var scene = new PreparedScene();
        var publication = new java.util.ArrayDeque<Runnable>();
        var cleanupFailure = new IllegalStateException("capture cleanup failed");
        int[] closed = {0};
        var uploader = new MinecraftEntityUploader() {
            @Override public UploadedEntity upload(MinecraftEntityMesh source) { throw new AssertionError(); }
            @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
                return new UploadJob() {
                    @Override public UploadedEntity finish() { throw new AssertionError(); }
                    @Override public void close() {
                        if (++closed[0] == 1) throw cleanupFailure;
                    }
                };
            }
        };
        var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {}, uploader,
                Runnable::run, publication::add);
        try (var group = geometry.beginUpdateGroup()) {
            geometry.put(new MinecraftEntityGeometry.Key(1, 1), revision(1), mesh(),
                    GeometryTransform.translation(0, 0, 0), 255);
            geometry.put(new MinecraftEntityGeometry.Key(1, 2), revision(1), mesh(),
                    GeometryTransform.translation(0, 0, 0), 255);
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(publication.removeFirst()::run);
        assertEquals(2, closed[0]);
        assertTrue(scene.jobs.isEmpty());
        assertSame(cleanupFailure,
                assertThrows(IllegalStateException.class, geometry::beginUpdateGroup).getCause());
    }

    @Test void coalescedRemovalAndRecreationKeepDistinctInstanceIdentity() throws Exception {
        var scene = new PreparedScene();
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var publication = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {},
                    ignored -> new Uploaded(0x1000), Runnable::run, publication);
            try {
                var key = new MinecraftEntityGeometry.Key(1, 1);
                geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                scene.jobs.getFirst().complete();
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                var old = assertInstanceOf(SceneEdit.SetInstance.class, scene.edits.getFirst().getFirst());
                publication.submit(() -> {
                    try { release.await(); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 255);
                geometry.drop(key);
                geometry.put(key, revision(3), mesh(), GeometryTransform.translation(3, 0, 0), 127);
                release.countDown();
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(2, scene.jobs.size());
                scene.jobs.getLast().complete();
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                var next = assertInstanceOf(SceneEdit.SetInstance.class, scene.edits.getLast().getFirst());
                assertNotSame(old.instance(), next.instance());
                assertEquals(GeometryTransform.translation(3, 0, 0), next.transform());
                assertEquals(1, scene.jobs.getFirst().releases);
            } finally { release.countDown(); geometry.close(); }
        }
    }

    @Test void blockedPublicationDoesNotBlockHostCaptureAndPendingMeshesCoalesce() throws Exception {
        var scene = new PreparedScene();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var channel = new dev.comfyfluffy.caustica.api.scene.SceneChannel() {
            @Override public InstanceId newInstance() { return scene.newInstance(); }
            @Override public dev.comfyfluffy.caustica.api.light.LightId newLight() { return scene.newLight(); }
            @Override public void edit(List<? extends SceneEdit> edits) {
                if (block.compareAndSet(true, false)) {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
                scene.edit(edits);
            }
        };
        var uploads = new ArrayList<Uploaded>();
        var closedInputs = new java.util.concurrent.atomic.AtomicInteger();
        var captures = new java.util.concurrent.atomic.AtomicInteger();
        var uploader = new MinecraftEntityUploader() {
            @Override public UploadedEntity upload(MinecraftEntityMesh source) { throw new AssertionError(); }
            @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
                captures.incrementAndGet();
                return new UploadJob() {
                    @Override public UploadedEntity finish() {
                        var upload = new Uploaded(0x1000);
                        uploads.add(upload);
                        return upload;
                    }
                    @Override public void close() { closedInputs.incrementAndGet(); }
                };
            }
        };
        try (var publication = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var geometry = new MinecraftEntityGeometry(scene, channel, new SceneId() {}, uploader,
                    Runnable::run, publication);
            try {
                var key = new MinecraftEntityGeometry.Key(1, 2);
                geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                block.set(true);
                scene.jobs.getFirst().complete();
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var capture = new java.util.concurrent.FutureTask<Void>(() -> {
                    for (int i = 2; i <= 100; i++) {
                        try (var group = geometry.beginUpdateGroup()) {
                            geometry.put(key, revision(i), mesh(), GeometryTransform.translation(i, 0, 0), 127);
                            group.submit();
                        }
                    }
                    return null;
                });
                Thread.ofPlatform().start(capture);
                capture.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(100, captures.get());
                assertEquals(1, scene.jobs.size());
                assertEquals(99, closedInputs.get());
                release.countDown();
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(2, scene.jobs.size());
                scene.jobs.getLast().complete();
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                var last = assertInstanceOf(SceneEdit.SetInstance.class, scene.edits.getLast().getFirst());
                assertEquals(GeometryTransform.translation(100, 0, 0), last.transform());
                assertEquals(100, closedInputs.get());
            } finally {
                release.countDown();
                geometry.close();
            }
        }
        uploads.forEach(upload -> assertEquals(1, upload.closeCount));
        scene.jobs.forEach(job -> assertEquals(1, job.releases));
    }

    @Test void placementGroupCommitsOnceOnWorkerAndAcknowledgesAfterCommit() throws Exception {
        var scene = new PreparedScene();
        var caller = Thread.currentThread();
        var acknowledged = new ArrayList<Thread>();
        try (var publication = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {},
                    ignored -> new Uploaded(0x1000), Runnable::run, publication);
            try {
                var first = new MinecraftEntityGeometry.Key(1, 1);
                var second = new MinecraftEntityGeometry.Key(1, 2);
                geometry.put(first, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
                geometry.put(second, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                scene.jobs.forEach(PreparedScene.Job::complete);
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                int before = scene.edits.size();
                try (var group = geometry.beginUpdateGroup()) {
                    geometry.transform(first, GeometryTransform.translation(1, 0, 0), 255,
                            () -> acknowledged.add(Thread.currentThread()));
                    geometry.transform(second, GeometryTransform.translation(2, 0, 0), 255,
                            () -> acknowledged.add(Thread.currentThread()));
                    assertEquals(before, scene.edits.size());
                    group.submit();
                }
                publication.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(before + 1, scene.edits.size());
                assertEquals(2, scene.edits.getLast().size());
                assertEquals(2, acknowledged.size());
                acknowledged.forEach(thread -> assertNotSame(caller, thread));
            } finally { geometry.close(); }
        }
    }

    @Test void publicationEventMeasuresReadinessBeforeThePublicationBoundary(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var output = directory.resolve("entity-publication.jfr");
        long beforeReady, afterReady, beforePublication, afterPublication;
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable(MinecraftEntityGeometry.EntityMeshPublicationEvent.class);
            recording.start();
            var scene = new PreparedScene();
            try (var geometry = geometry(scene,
                    ignored -> new Uploaded(0x1000))) {
                var key = new MinecraftEntityGeometry.Key(1, 2);
                geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
                beforeReady = System.nanoTime();
                beforePublication = beforeReady;
                scene.jobs.getFirst().complete();
                afterReady = System.nanoTime();
                afterPublication = afterReady;
                assertEquals(1, scene.edits.size());
                var removed = new MinecraftEntityGeometry.Key(1, 3);
                geometry.put(removed, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
                geometry.drop(removed);
                scene.jobs.getLast().complete();
                flush(geometry);
            }
            recording.stop();
            recording.dump(output);
        }
        var events = jdk.jfr.consumer.RecordingFile.readAllEvents(output).stream()
                .filter(event -> event.getEventType().getName().equals("dev.comfyfluffy.caustica.EntityMeshPublication"))
                .toList();
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals(1, event.getLong("keyDomain"));
        assertEquals(2, event.getLong("keyValue"));
        assertTrue(event.getLong("readyNanos") >= beforeReady);
        assertTrue(event.getLong("readyNanos") <= afterReady);
        assertTrue(event.getLong("publishedNanos") >= beforePublication);
        assertTrue(event.getLong("publishedNanos") <= afterPublication);
    }

    @Test void replacementKeepsOldMeshAndUsesCurrentTransformWhenReady() {
        var scene = new PreparedScene();
        var uploads = new ArrayList<>(List.of(new Uploaded(0x1000), new Uploaded(0x2000)));
        var first = uploads.getFirst();
        var geometry = geometry(scene, ignored -> uploads.removeFirst());
        var key = new MinecraftEntityGeometry.Key(1, 2);
        int[] acknowledgments = {0};
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255,
                () -> acknowledgments[0]++);
        assertTrue(scene.edits.isEmpty());
        assertEquals(0, acknowledgments[0]);
        scene.jobs.get(0).complete();
        flush(geometry);
        assertEquals(1, acknowledgments[0]);
        var initial = (SceneEdit.SetInstance<?>) scene.edits.getFirst().getFirst();
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 255);
        geometry.transform(key, GeometryTransform.translation(9, 0, 0), 127);
        assertFalse(first.closed);
        assertInstanceOf(SceneEdit.SetTransform.class, scene.edits.getLast().getFirst());
        scene.jobs.get(1).complete();
        flush(geometry);
        var replacement = (SceneEdit.SetInstance<?>) scene.edits.getLast().getFirst();
        assertSame(initial.instance(), replacement.instance());
        assertNotSame(initial.mesh(), replacement.mesh());
        assertEquals(GeometryTransform.translation(9, 0, 0), replacement.transform());
        assertEquals(127, replacement.mask());
        assertTrue(first.closed);
        assertEquals(1, scene.jobs.get(0).releases);
        geometry.close();
    }

    @Test void continuouslyChangingCapturesPublishAndOnlyPrepareTheNewestQueuedMesh() {
        var scene = new PreparedScene();
        var uploads = new ArrayList<Uploaded>();
        var captured = new ArrayList<MinecraftEntityMesh>();
        var accepted = new ArrayList<Integer>();
        var geometry = geometry(scene, mesh -> {
            captured.add(mesh);
            var upload = new Uploaded(0x1000L * (uploads.size() + 1));
            uploads.add(upload);
            return upload;
        });
        var key = new MinecraftEntityGeometry.Key(1, 2);
        var firstMesh = mesh();
        geometry.put(key, revision(1), firstMesh, GeometryTransform.translation(1, 0, 0), 255,
                () -> accepted.add(1));
        MinecraftEntityMesh newest = null;
        for (int revision = 2; revision <= 100; revision++) {
            newest = mesh();
            int version = revision;
            geometry.put(key, revision(version), newest, GeometryTransform.translation(version, 0, 0), 255,
                    () -> accepted.add(version));
        }
        assertEquals(1, scene.jobs.size());
        assertEquals(List.of(firstMesh), captured);
        scene.jobs.getFirst().complete();
        flush(geometry);
        assertEquals(List.of(1), accepted);
        assertEquals(2, scene.jobs.size());
        assertSame(newest, captured.getLast());
        var initial = (SceneEdit.SetInstance<?>) scene.edits.getLast().getFirst();
        assertEquals(GeometryTransform.translation(100, 0, 0), initial.transform());
        assertNotSame(initial.mesh(), scene.jobs.get(1).refitSource);
        assertSame(initial.mesh().instanceDataType(), scene.jobs.get(1).refitSource.instanceDataType());
        assertFalse(uploads.getFirst().closed);

        for (int revision = 101; revision <= 200; revision++) {
            newest = mesh();
            int version = revision;
            geometry.put(key, revision(version), newest, GeometryTransform.translation(version, 0, 0), 255,
                    () -> accepted.add(version));
        }
        assertEquals(2, scene.jobs.size());
        scene.jobs.get(1).complete();
        flush(geometry);
        assertEquals(List.of(1, 100), accepted);
        assertEquals(3, scene.jobs.size());
        assertSame(newest, captured.getLast());
        var replacement = (SceneEdit.SetInstance<?>) scene.edits.getLast().getFirst();
        assertSame(initial.instance(), replacement.instance());
        assertEquals(GeometryTransform.translation(200, 0, 0), replacement.transform());
        assertNotSame(replacement.mesh(), scene.jobs.get(2).refitSource);
        assertSame(replacement.mesh().instanceDataType(), scene.jobs.get(2).refitSource.instanceDataType());
        assertTrue(uploads.getFirst().closed);
        geometry.stop();
        scene.jobs.get(2).complete();
        geometry.close();
        assertEquals(List.of(1, 100), accepted);
        uploads.forEach(upload -> assertEquals(1, upload.closeCount));
        scene.jobs.forEach(job -> assertEquals(1, job.releases));
    }

    @Test void droppedPreparationCannotReplaceARecreatedInstance() {
        var scene = new PreparedScene();
        var geometry = geometry(scene, ignored -> new Uploaded(0x1000));
        var key = new MinecraftEntityGeometry.Key(1, 2);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 255);
        geometry.drop(key);
        geometry.put(key, revision(3), mesh(), GeometryTransform.translation(3, 0, 0), 255);
        assertEquals(2, scene.jobs.size());
        scene.jobs.get(0).complete();
        flush(geometry);
        assertTrue(scene.edits.isEmpty());
        assertEquals(1, scene.jobs.get(0).releases);
        scene.jobs.get(1).complete();
        flush(geometry);
        var placement = (SceneEdit.SetInstance<?>) scene.edits.getLast().getFirst();
        assertEquals(GeometryTransform.translation(3, 0, 0), placement.transform());
        geometry.close();
        assertEquals(1, scene.jobs.get(1).releases);
    }

    @Test void cancelledCaptureRestoresPendingPreparationAndDiscardsQueuedReplacement() {
        var scene = new PreparedScene();
        var geometry = geometry(scene, ignored -> new Uploaded(0x1000));
        var key = new MinecraftEntityGeometry.Key(1, 2);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
        try (var group = geometry.beginUpdateGroup()) {
            geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 255);
            scene.jobs.get(0).complete();
        }
        flush(geometry);
        assertEquals(1, scene.edits.size());
        assertEquals(1, scene.jobs.size());
        var placement = (SceneEdit.SetInstance<?>) scene.edits.getLast().getFirst();
        assertEquals(GeometryTransform.translation(0, 0, 0), placement.transform());
        geometry.close();
        assertEquals(1, scene.jobs.get(0).releases);
    }

    @Test void rejectedCaptureEditLeavesLiveStateAndReportsWorkerFailure() {
        var scene = new PreparedScene();
        var geometry = geometry(scene, ignored -> new Uploaded(0x1000));
        var key = new MinecraftEntityGeometry.Key(1, 2);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
        scene.jobs.get(0).complete();
        flush(geometry);
        try (var group = geometry.beginUpdateGroup()) {
            geometry.put(key, revision(2), mesh(), GeometryTransform.translation(2, 0, 0), 255);
            scene.reject = true;
            group.submit();
        }
        assertThrows(IllegalStateException.class, geometry::beginUpdateGroup);
        assertEquals(1, scene.edits.size());
        assertEquals(1, scene.jobs.size());
        geometry.close();
        assertEquals(1, scene.jobs.get(0).releases);
    }

    @Test void rejectedPublicationPreservesItsCauseWhenUploadCleanupFails() {
        var scene = new PreparedScene();
        var cleanupFailure = new IllegalStateException("upload cleanup failed");
        var upload = new Uploaded(0x1000) {
            @Override public void close() {
                super.close();
                throw cleanupFailure;
            }
        };
        var geometry = geometry(scene, ignored -> upload);
        geometry.put(new MinecraftEntityGeometry.Key(1, 2), revision(1), mesh(),
                GeometryTransform.translation(0, 0, 0), 255);
        scene.reject = true;
        scene.jobs.getFirst().complete();

        var failure = assertThrows(IllegalStateException.class, geometry::beginUpdateGroup).getCause();
        assertNotSame(cleanupFailure, failure);
        assertEquals("rejected edit", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(cleanupFailure, failure.getSuppressed()[0]);
        assertTrue(scene.edits.isEmpty());
        assertEquals(1, upload.closeCount);
        assertEquals(1, scene.jobs.getFirst().releases);
        geometry.close();
        assertEquals(1, upload.closeCount);
        assertEquals(1, scene.jobs.getFirst().releases);
    }

    @Test void shutdownClosesLaterCompletionWithoutPublishing() {
        var scene = new PreparedScene();
        var upload = new Uploaded(0x1000);
        var geometry = geometry(scene, ignored -> upload);
        geometry.put(new MinecraftEntityGeometry.Key(1, 2), revision(1), mesh(),
                GeometryTransform.translation(0, 0, 0), 255);
        geometry.close();
        scene.jobs.get(0).complete();
        assertTrue(upload.closed);
        assertEquals(1, scene.jobs.get(0).releases);
        assertTrue(scene.edits.isEmpty());
    }

    @Test void textureCaptureStaysOnCallerAndPackingRunsBeforeMeshPreparationOnWorker() throws Exception {
        var scene = new PreparedScene();
        var queue = new java.util.ArrayDeque<Runnable>();
        var caller = Thread.currentThread();
        var packedOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var captured = new ArrayList<MinecraftEntityMesh>();
        var uploader = new MinecraftEntityUploader() {
            @Override public UploadedEntity upload(MinecraftEntityMesh source) { throw new AssertionError(); }
            @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
                assertSame(caller, Thread.currentThread());
                captured.add(source);
                return new UploadJob() {
                    @Override public UploadedEntity finish() {
                        packedOn.set(Thread.currentThread());
                        assertTrue(scene.jobs.isEmpty());
                        return new Uploaded(0x1000);
                    }
                    @Override public void close() { }
                };
            }
        };
        var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {}, uploader, queue::add);
        var key = new MinecraftEntityGeometry.Key(1, 1);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
        var newest = mesh();
        geometry.put(key, revision(2), newest, GeometryTransform.translation(5, 0, 0), 255);
        assertEquals(1, queue.size());
        assertEquals(2, captured.size());
        assertTrue(scene.jobs.isEmpty());
        var worker = Thread.ofPlatform().start(queue.remove());
        worker.join();
        assertSame(worker, packedOn.get());
        assertEquals(1, scene.jobs.size());
        scene.jobs.getFirst().complete();
        flush(geometry);
        assertEquals(2, captured.size());
        assertSame(newest, captured.getLast());
        assertEquals(1, queue.size());
        geometry.drop(key);
        queue.remove().run();
        geometry.close();
    }

    @Test void queuedReplacementRetainsRefitSourceUntilCancelledJobReleasesItsInputs() {
        var scene = new PreparedScene();
        var queue = new java.util.ArrayDeque<Runnable>();
        var uploads = new ArrayList<Uploaded>();
        var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {}, ignored -> {
            var uploaded = new Uploaded(0x1000);
            uploads.add(uploaded);
            return uploaded;
        }, queue::add);
        var key = new MinecraftEntityGeometry.Key(1, 1);
        geometry.put(key, revision(1), mesh(), GeometryTransform.translation(0, 0, 0), 255);
        queue.remove().run();
        scene.jobs.getFirst().complete();
        flush(geometry);
        geometry.put(key, revision(2), mesh(), GeometryTransform.translation(1, 0, 0), 255);
        geometry.drop(key);
        assertEquals(0, scene.jobs.getFirst().releases);
        assertEquals(1, uploads.size());
        assertTrue(uploads.getFirst().closed);
        queue.remove().run();
        assertEquals(1, scene.jobs.getFirst().releases);
        assertTrue(uploads.getLast().closed);
        assertEquals(1, scene.jobs.size());
        geometry.close();
    }

    @Test void shutdownWaitsForPackingBeforeUploaderTeardownAndClosesLateGpuCompletion() throws Exception {
        var scene = new PreparedScene();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var uploaderClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var jobClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var uploaded = new Uploaded(0x1000);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var uploader = new MinecraftEntityUploader() {
                @Override public UploadedEntity upload(MinecraftEntityMesh source) { throw new AssertionError(); }
                @Override public UploadJob prepareUpload(MinecraftEntityMesh source) {
                    return new UploadJob() {
                        @Override public UploadedEntity finish() {
                            entered.countDown();
                            try { proceed.await(); }
                            catch (InterruptedException failure) { throw new AssertionError(failure); }
                            return uploaded;
                        }
                        @Override public void close() { jobClosed.set(true); }
                    };
                }
                @Override public void close() { assertTrue(jobClosed.get()); uploaderClosed.set(true); }
            };
            var geometry = new MinecraftEntityGeometry(scene, scene, new SceneId() {}, uploader, worker);
            geometry.put(new MinecraftEntityGeometry.Key(1, 1), revision(1), mesh(),
                    GeometryTransform.translation(0, 0, 0), 255);
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var closing = new java.util.concurrent.FutureTask<Void>(() -> { geometry.close(); return null; });
            Thread.ofPlatform().start(closing);
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> closing.get(50, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertFalse(uploaderClosed.get());
            } finally { proceed.countDown(); }
            closing.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(uploaderClosed.get());
            scene.jobs.getFirst().complete();
            assertEquals(1, uploaded.closeCount);
            assertEquals(1, scene.jobs.getFirst().releases);
        }
    }

    private static MinecraftEntityGeometry geometry(PreparedScene scene, MinecraftEntityUploader uploader) {
        return new MinecraftEntityGeometry(scene, scene, new SceneId() {}, uploader, Runnable::run);
    }

    private static void flush(MinecraftEntityGeometry geometry) {
        try (var group = geometry.beginUpdateGroup()) { group.submit(); }
    }

    private static MinecraftEntityMesh mesh() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        var triangle = new MinecraftEntityMesh.Triangle(material, MinecraftEntityMesh.Coverage.OPAQUE,
                0);
        return new MinecraftEntityMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2}, new float[] {0, 0, 1, 0, 0, 1},
                new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, List.of(triangle), 17);
    }

    private static MinecraftEntityGeometry.MeshRevision revision(long content) {
        return new MinecraftEntityGeometry.MeshRevision(0, content, 17);
    }

    private static class Uploaded implements MinecraftEntityUploader.UploadedEntity {
        final long address;
        boolean closed;
        int closeCount;

        Uploaded(long address) { this.address = address; }

        @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() {
            var positions = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), 36), 12,
                    ResourceOwner.none());
            var indices = new MeshBuild.Stream(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address + 0x100), 12), 4,
                    ResourceOwner.none());
            var surface = new MeshBuild.SurfaceSlot<>(new SurfaceId<
                    MinecraftProgramTypes.PrimitiveData, MinecraftProgramTypes.InstanceData>() { },
                    MinecraftProgramTypes.PRIMITIVE_DATA.data(address + 0x200),
                    new MeshBuild.CoveragePolicy.Opaque());
            return new MeshBuild<>(positions, indices, 3, new MeshBuild.IndexRevision(17), MeshBuild.BuildPolicy.REFITTABLE,
                    List.of(new MeshBuild.Geometry<>(surface, null, 0, 3)));
        }

        @Override public dev.comfyfluffy.caustica.api.program.ShaderData<
                MinecraftProgramTypes.InstanceData> instanceData() {
            return MinecraftProgramTypes.INSTANCE_DATA.data(address + 0x300);
        }

        @Override public void close() { closed = true; closeCount++; }
    }

}
