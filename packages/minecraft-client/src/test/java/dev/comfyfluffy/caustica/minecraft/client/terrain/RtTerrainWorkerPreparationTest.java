package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.api.geometry.*;
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
    @Test void uploadsWithoutARenderPassAndDiscardsReadyResultAtUnbind() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.submit();
            assertTrue(fixture.submitted.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.uploadThread.get().startsWith("rt-worker-"));
            assertEquals(1, fixture.outstanding());
            fixture.ready.complete(fixture.mesh);
            fixture.terrain.unbindGeometry(fixture.geometry);
            assertEquals(1, fixture.meshClosed.get());
            assertEquals(1, fixture.uploadClosed.get());
            assertEquals(0, fixture.outstanding());
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
                return new RtTerrainMesher.CpuSection(null, new float[0]);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.terrain.submitBuild(fixture.build(), fixture.geometry,
                    () -> { throw new AssertionError("queued job must be cancelled"); });
            fixture.terrain.unbindGeometry(fixture.geometry);
            assertEquals(0, fixture.outstanding());
            assertNull(fixture.uploadThread.get());
        }
    }

    @Test void oneBoundaryDrainsAllCompletedWork() throws Exception {
        try (var fixture = new Fixture()) {
            for (int i = 0; i < 192; i++) {
                fixture.terrain.submitBuild(fixture.build(), fixture.geometry,
                        () -> new RtTerrainMesher.CpuSection(null, new float[0]));
            }
            var finished = new CountDownLatch(1);
            fixture.workers.submit(finished::countDown);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(192, fixture.outstanding());
            fixture.terrain.drainCompleted(new ResourcePackEpoch(0));
            assertEquals(0, fixture.outstanding());
            assertEquals(192, fixture.updates().ready().size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RtWorkerPool workers = new RtWorkerPool(1);
        final RtTerrain terrain = new RtTerrain(workers, MinecraftTelemetry.disabled());
        final CountDownLatch submitted = new CountDownLatch(1);
        final AtomicReference<String> uploadThread = new AtomicReference<>();
        final AtomicInteger uploadClosed = new AtomicInteger(), meshClosed = new AtomicInteger();
        final CompletableFuture<ReadyMesh<MinecraftProgramTypes.InstanceData>> ready = new CompletableFuture<>();
        Runnable beforeUpload = () -> {};
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
        }, null, null, source -> {
            uploadThread.set(Thread.currentThread().getName());
            beforeUpload.run();
            return new MinecraftTerrainUploader.UploadedSection() {
                @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() { return null; }
                @Override public dev.comfyfluffy.caustica.api.program.ShaderData<MinecraftProgramTypes.InstanceData>
                        instanceData() { throw new AssertionError(); }
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
                                    MinecraftTerrainMesh.Coverage.OPAQUE, 0, 3, 0.5f)), 1), new float[0]));
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
        @Override public void close() { terrain.shutdown(); }
    }
}
