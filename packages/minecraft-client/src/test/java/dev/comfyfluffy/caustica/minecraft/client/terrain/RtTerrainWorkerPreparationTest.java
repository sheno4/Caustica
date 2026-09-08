package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RtTerrainWorkerPreparationTest {
    @Test void dirtyInvalidationDoesNotWaitForTheCoordinationStateLock() throws Exception {
        try (var fixture = new Fixture()) {
            var request = fixture.build().request();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Object lock = fixture.preparationLock();
            fixture.workers.submitCoordination(() -> {
                synchronized (lock) {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }
            }, () -> { });
            try (var render = Executors.newSingleThreadExecutor()) {
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    render.submit(() -> fixture.terrain.markBlocksDirty(1, 1, 1, 1, 1, 1))
                            .get(2, TimeUnit.SECONDS);
                    assertFalse(request.valid());
                } finally { release.countDown(); }
            }
            fixture.awaitPublication();
        }
    }

    @Test void uploadsAndPublishesWithoutARenderPass() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.submit();
            assertTrue(fixture.submitted.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.uploadThread.get().startsWith("rt-worker-"));
            assertEquals(1, fixture.outstanding());
            fixture.ready.complete(fixture.mesh);
            assertTrue(fixture.published.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.publishThread.get().equals("rt-terrain-publication"));
            fixture.terrain.unbindGeometry(fixture.geometry);
            fixture.geometry.close();
            assertEquals(1, fixture.meshClosed.get());
            assertEquals(1, fixture.uploadClosed.get());
            assertEquals(0, fixture.outstanding());
        }
    }

    @Test void publicationDoesNotWaitForMeshingBacklog() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.submit();
            assertTrue(fixture.submitted.await(5, TimeUnit.SECONDS));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.workers.submit(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                fixture.ready.complete(fixture.mesh);
                assertTrue(fixture.published.await(5, TimeUnit.SECONDS));
                assertEquals("rt-terrain-publication", fixture.publishThread.get());
            } finally { release.countDown(); }
        }
    }

    @Test void unbindJoinsUploadBeforeReturningAndRejectsLateGpuCompletion() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var release = new Semaphore(0);
            fixture.beforeUpload = () -> {
                entered.countDown();
                release.acquireUninterruptibly();
            };
            fixture.submit();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (var executor = Executors.newSingleThreadExecutor()) {
                var unbinding = executor.submit(() -> fixture.terrain.unbindGeometry(fixture.geometry));
                try {
                    assertThrows(TimeoutException.class, () -> unbinding.get(100, TimeUnit.MILLISECONDS));
                } finally {
                    release.release();
                }
                unbinding.get(5, TimeUnit.SECONDS);
            }
            assertTrue(fixture.submitted.await(5, TimeUnit.SECONDS));
            fixture.ready.complete(fixture.mesh);
            assertEquals(1, fixture.meshClosed.get());
            assertEquals(1, fixture.uploadClosed.get());
            assertEquals(0, fixture.outstanding());
            fixture.terrain.bindGeometry(fixture.geometry);
            var restarted = new CountDownLatch(1);
            fixture.workers.submit(restarted::countDown);
            assertTrue(restarted.await(5, TimeUnit.SECONDS));
        }
    }

    @Test void shutdownAccountsForCancelledQueuedAndRunningExtraction() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            fixture.terrain.submitBuild(fixture.build(), fixture.geometry, () -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                return new RtTerrainMesher.CpuSection(null, List.of());
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.terrain.submitBuild(fixture.build(), fixture.geometry,
                    () -> { throw new AssertionError("queued job must be cancelled"); });
            fixture.terrain.unbindGeometry(fixture.geometry);
            assertEquals(0, fixture.outstanding());
            assertNull(fixture.uploadThread.get());
        }
    }

    @Test void completedEmptySectionsPublishWithoutARenderPass() throws Exception {
        try (var fixture = new Fixture()) {
            var builds = new java.util.ArrayList<RtTerrain.Build>();
            for (int i = 0; i < 192; i++) builds.add(fixture.build());
            for (var build : builds) fixture.terrain.submitBuild(build, fixture.geometry,
                    () -> new RtTerrainMesher.CpuSection(null, List.of()));
            fixture.awaitPublication();
            assertEquals(0, fixture.outstanding());
            assertTrue(fixture.updates().ready().isEmpty());
            assertTrue(fixture.updates().sections.values().stream().allMatch(section -> section.ready));
        }
    }

    @Test void removalDuringEditPreparationRejectsThePreparedReplacement() throws Exception {
        rejectsChangedRequestDuringPublication(true);
    }

    @Test void dirtyDuringEditPreparationRejectsThePreparedReplacement() throws Exception {
        rejectsChangedRequestDuringPublication(false);
    }

    private void rejectsChangedRequestDuringPublication(boolean remove) throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.beforeInstanceData = () -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            };
            fixture.submit();
            assertTrue(fixture.submitted.await(5, TimeUnit.SECONDS));
            fixture.ready.complete(fixture.mesh);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                synchronized (fixture.preparationLock()) {
                    if (remove) fixture.updates().remove(0L);
                    else fixture.terrain.markBlocksDirty(1, 1, 1, 1, 1, 1);
                }
            } finally { release.countDown(); }
            fixture.awaitPublication();
            assertNull(fixture.publishThread.get());
            assertFalse(fixture.geometry.hasSection(0));
            assertEquals(1, fixture.meshClosed.get());
            assertEquals(1, fixture.uploadClosed.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RtWorkerPool workers = new RtWorkerPool(1);
        final RtTerrain terrain = new RtTerrain(workers, MinecraftTelemetry.disabled());
        final CountDownLatch submitted = new CountDownLatch(1);
        final AtomicReference<String> uploadThread = new AtomicReference<>();
        final AtomicReference<String> publishThread = new AtomicReference<>();
        final CountDownLatch published = new CountDownLatch(1);
        final AtomicInteger uploadClosed = new AtomicInteger(), meshClosed = new AtomicInteger();
        final CompletableFuture<ReadyMesh<MinecraftProgramTypes.InstanceData>> ready = new CompletableFuture<>();
        Runnable beforeUpload = () -> {};
        Runnable beforeInstanceData = () -> {};
        final ReadyMesh<MinecraftProgramTypes.InstanceData> mesh = new ReadyMesh<>() {
            @Override public ShaderDataType<MinecraftProgramTypes.InstanceData> instanceDataType() {
                return MinecraftProgramTypes.INSTANCE_DATA;
            }
            @Override public ReadyMesh<MinecraftProgramTypes.InstanceData> retain() { throw new AssertionError(); }
            @Override public void close() { meshClosed.incrementAndGet(); }
        };
        final MinecraftTerrainGeometry geometry = new MinecraftTerrainGeometry(new MeshPreparer() {
            @Override @SuppressWarnings("unchecked")
            public <N> CompletableFuture<ReadyMesh<N>> prepare(ShaderDataType<N> type, MeshBuild<N> build,
                                                              ReadyMesh<N> source) {
                submitted.countDown();
                return (CompletableFuture<ReadyMesh<N>>) (CompletableFuture<?>) ready;
            }
        }, new SceneChannel() {
            @Override public InstanceId newInstance() { return new InstanceId() {}; }
            @Override public LightId newLight() { return new LightId() {}; }
            @Override public void edit(List<? extends SceneEdit> edits) {
                publishThread.set(Thread.currentThread().getName());
                published.countDown();
            }
        }, new SceneId() {}, source -> {
            uploadThread.set(Thread.currentThread().getName());
            beforeUpload.run();
            return new MinecraftTerrainUploader.UploadedSection() {
                @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() { return null; }
                @Override public dev.comfyfluffy.caustica.api.program.ShaderData<MinecraftProgramTypes.InstanceData>
                        instanceData() { beforeInstanceData.run(); return new dev.comfyfluffy.caustica.api.program.ShaderData<>(
                            MinecraftProgramTypes.INSTANCE_DATA, 0, dev.comfyfluffy.caustica.api.resource.ResourceOwner.none()); }
                @Override public void close() { uploadClosed.incrementAndGet(); }
            };
        });

        Fixture() { terrain.bindGeometry(geometry); }
        long nextKey;
        RtTerrain.Build build() {
            var updates = updates();
            long key = nextKey++;
            updates.want(key);
            return new RtTerrain.Build(updates.sections.get(key).request, 0, new ResourcePackEpoch(0),
                    1, null, null, null, null);
        }
        void submit() {
            terrain.submitBuild(build(), geometry, () -> new RtTerrainMesher.CpuSection(
                    new MinecraftTerrainMesh(new float[]{0,0,0, 1,0,0, 0,1,0}, new int[]{0,1,2},
                            new float[6], new float[MinecraftTerrainMesh.PRIMITIVE_FLOATS],
                            List.of(new MinecraftTerrainMesh.Geometry(MinecraftTerrainMesh.ProgramCategory.MATERIAL,
                                    MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f)), 1), List.of()));
        }
        @SuppressWarnings("unchecked")
        TerrainUpdates<RtTerrain.Build> updates() {
            try {
                var field = RtTerrain.class.getDeclaredField("updates");
                field.setAccessible(true);
                return (TerrainUpdates<RtTerrain.Build>) field.get(terrain);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        int outstanding() throws Exception {
            var field = RtTerrain.class.getDeclaredField("outstandingBuilds");
            field.setAccessible(true);
            return ((AtomicInteger) field.get(terrain)).get();
        }
        Object preparationLock() throws Exception {
            var field = RtTerrain.class.getDeclaredField("preparationLock");
            field.setAccessible(true);
            return field.get(terrain);
        }
        void awaitPublication() throws Exception {
            var extracted = new CountDownLatch(1);
            workers.submit(extracted::countDown);
            assertTrue(extracted.await(5, TimeUnit.SECONDS));
            workers.coordinateAndWait(() -> { });
            for (int i = 0; i < 2; i++) {
                var finished = new CountDownLatch(1);
                workers.submitPublication(finished::countDown, () -> {});
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                workers.coordinateAndWait(() -> { });
            }
        }
        @Override public void close() { terrain.shutdown(); geometry.close(); }
    }
}
