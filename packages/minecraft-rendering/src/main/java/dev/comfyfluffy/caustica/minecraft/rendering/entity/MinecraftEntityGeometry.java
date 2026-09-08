package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import java.util.*;
import java.util.concurrent.*;

/** Captures host inputs on the caller and publishes retained entity deltas on one worker. */
public final class MinecraftEntityGeometry implements MinecraftWorldSessionContribution {
    private static final jdk.jfr.EventType UPLOAD_EVENT =
            jdk.jfr.EventType.getEventType(EntityMeshUploadEvent.class);
    private static final com.sun.management.ThreadMXBean UPLOAD_THREADS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    private static final jdk.jfr.EventType PUBLICATION_EVENT =
            jdk.jfr.EventType.getEventType(EntityMeshPublicationEvent.class);
    private final MeshPreparer meshes;
    private final SceneChannel channel;
    private final SceneId scene;
    private final MinecraftEntityUploader uploader;
    private final Executor packingExecutor;
    private final Executor publicationExecutor;
    private final boolean ownsExecutors;
    private final Set<CompletableFuture<Void>> packing = ConcurrentHashMap.newKeySet();
    private final Map<Key, Resident> residents = new LinkedHashMap<>();
    private final Object pendingLock = new Object();
    private Map<Key, PendingChanges> pending = new LinkedHashMap<>();
    private boolean drainScheduled;
    private final Object submissionLock = new Object();
    private boolean accepting = true;
    private volatile Throwable failure;
    private UpdateGroup group;
    private boolean stopped;
    private boolean closed;
    private CompletableFuture<Void> stopResult;

    public MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                                   MinecraftEntityUploader uploader) {
        this(meshes, channel, scene, uploader, Executors.newFixedThreadPool(2,
                        Thread.ofPlatform().daemon().name("caustica-entity-upload-", 0).factory()),
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("caustica-entity-publication").factory()), true);
    }

    MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                            MinecraftEntityUploader uploader, Executor packingExecutor) {
        this(meshes, channel, scene, uploader, packingExecutor, Runnable::run, false);
    }

    MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                            MinecraftEntityUploader uploader, Executor packingExecutor, Executor publicationExecutor) {
        this(meshes, channel, scene, uploader, packingExecutor, publicationExecutor, false);
    }

    private MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                                    MinecraftEntityUploader uploader, Executor packingExecutor,
                                    Executor publicationExecutor, boolean ownsExecutors) {
        this.packingExecutor = packingExecutor;
        this.publicationExecutor = publicationExecutor;
        this.ownsExecutors = ownsExecutors;
        this.meshes = meshes;
        this.channel = channel;
        this.scene = scene;
        this.uploader = uploader;
    }

    public synchronized UpdateGroup beginUpdateGroup() {
        requireRunning();
        if (group != null) throw new IllegalStateException("entity update group already active");
        group = new UpdateGroup();
        return group;
    }

    public void put(Key key, MeshRevision revision, MinecraftEntityMesh mesh,
                    GeometryTransform transform, int mask) {
        put(key, revision, mesh, transform, mask, null);
    }

    public synchronized void put(Key key, MeshRevision revision, MinecraftEntityMesh mesh,
                                 GeometryTransform transform, int mask, Runnable accepted) {
        requireRunning();
        var capture = new Capture(revision, uploader.prepareUpload(mesh), accepted);
        emit(new Put(key, capture, transform, mask));
    }

    public void transform(Key key, GeometryTransform transform, int mask) {
        transform(key, transform, mask, null);
    }

    public synchronized void transform(Key key, GeometryTransform transform, int mask, Runnable accepted) {
        requireRunning();
        emit(new Transform(key, transform, mask, accepted));
    }

    public synchronized void drop(Key key) {
        requireRunning();
        emit(new Drop(key));
    }

    private void emit(Change change) {
        if (group != null) group.changes.add(change);
        else submit(List.of(change));
    }

    private void submit(List<Change> changes) {
        var discarded = new ArrayList<Capture>();
        synchronized (pendingLock) {
            for (var change : changes)
                pending.computeIfAbsent(change.key(), ignored -> new PendingChanges()).merge(change, discarded);
            if (!drainScheduled) {
                drainScheduled = true;
                executePublication(this::drain);
            }
        }
        if (!discarded.isEmpty()) executePacking(() -> {
            Throwable failure = null;
            for (var capture : discarded) failure = cleanup(failure, capture::close);
            throwFailure(failure);
        });
    }

    private void drain() {
        Map<Key, PendingChanges> selected;
        synchronized (pendingLock) {
            selected = pending;
            pending = new LinkedHashMap<>();
            drainScheduled = false;
        }
        var changes = new ArrayList<Change>();
        selected.forEach((key, delta) -> {
            if (delta.dropped) changes.add(new Drop(key));
            if (delta.put != null) changes.add(delta.put);
            if (delta.transform != null) changes.add(delta.transform);
        });
        if (failure != null) { closeCaptures(changes); return; }
        apply(changes);
    }

    /** Worker failures invalidate subsequent host submissions, including failures during abandoned-input cleanup. */
    private void executePublication(Runnable action) {
        publicationExecutor.execute(() -> {
            try { action.run(); }
            catch (Throwable thrown) { failure = thrown; }
        });
    }

    private void executePacking(Runnable action) {
        var terminal = new CompletableFuture<Void>();
        packing.add(terminal);
        try {
            packingExecutor.execute(() -> {
                try { action.run(); }
                catch (Throwable thrown) { failure = thrown; }
                finally { packing.remove(terminal); terminal.complete(null); }
            });
        } catch (RuntimeException | Error thrown) {
            packing.remove(terminal);
            throw thrown;
        }
    }

    /** Only touched residents are staged; one channel edit commits the group's ready placements. */
    private void apply(List<Change> changes) {
        var changed = new LinkedHashMap<Key, Resident>();
        var edits = new ArrayList<SceneEdit>();
        var retired = new ArrayList<Generation>();
        var discarded = new ArrayList<Capture>();
        var cancelled = new ArrayList<Preparation>();
        var accepted = new ArrayList<Runnable>();
        try {
            for (var change : changes) {
                Key key = change.key();
                Resident prior = changed.containsKey(key) ? changed.get(key) : residents.get(key);
                switch (change) {
                    case Put put -> {
                        if (prior != null && prior.revision.equals(put.capture.revision)) {
                            discarded.add(put.capture);
                            if (prior.live != null) edits.add(new SceneEdit.SetTransform(
                                    prior.instance, put.transform, put.mask));
                            changed.put(key, new Resident(prior.instance, prior.revision, put.transform,
                                    put.mask, prior.live, prior.request, prior.queued));
                        } else {
                            var instance = prior == null ? channel.newInstance() : prior.instance;
                            if (prior != null && prior.live != null)
                                edits.add(new SceneEdit.SetTransform(instance, put.transform, put.mask));
                            if (prior != null && prior.queued != null) discarded.add(prior.queued);
                            changed.put(key, new Resident(instance, put.capture.revision, put.transform,
                                    put.mask, prior == null ? null : prior.live,
                                    prior == null ? null : prior.request, put.capture));
                        }
                    }
                    case Transform transform -> {
                        if (prior == null) continue;
                        if (prior.live != null) {
                            edits.add(new SceneEdit.SetTransform(prior.instance, transform.transform, transform.mask));
                            if (transform.accepted != null) accepted.add(transform.accepted);
                        }
                        changed.put(key, new Resident(prior.instance, prior.revision, transform.transform,
                                transform.mask, prior.live, prior.request, prior.queued));
                    }
                    case Drop ignored -> {
                        if (prior == null) continue;
                        if (prior.live != null) {
                            edits.add(new SceneEdit.DropInstance(prior.instance));
                            retired.add(prior.live);
                        }
                        if (prior.queued != null) discarded.add(prior.queued);
                        if (prior.request != null) cancelled.add(prior.request);
                        changed.put(key, null);
                    }
                }
            }
            if (!edits.isEmpty()) channel.edit(edits);
        } catch (RuntimeException | Error thrown) {
            cleanup(thrown, () -> closeCaptures(changes));
            throw thrown;
        }
        changed.forEach((key, resident) -> {
            if (resident == null) residents.remove(key); else residents.put(key, resident);
        });
        cancelled.forEach(request -> request.cancelled = true);
        Throwable retirementFailure = null;
        for (var capture : discarded) retirementFailure = cleanup(retirementFailure, capture::close);
        for (var generation : retired) retirementFailure = cleanup(retirementFailure, generation::close);
        throwFailure(retirementFailure);
        accepted.forEach(Runnable::run);
        for (var key : changed.keySet()) startQueued(key);
    }

    /** One running build and one newest captured upload bound deformation work without starving publication. */
    private void startQueued(Key key) {
        var resident = residents.get(key);
        if (resident == null || resident.request != null || resident.queued == null) return;
        var capture = resident.queued;
        var request = new Preparation(capture.accepted);
        var source = resident.live == null ? null : resident.live.mesh.retain();
        residents.put(key, new Resident(resident.instance, resident.revision, resident.transform,
                resident.mask, resident.live, request, null));
        long queuedNanos = UPLOAD_EVENT.isEnabled() ? System.nanoTime() : 0L;
        try {
            executePacking(() -> prepareOnWorker(key, request, capture.upload, source, queuedNanos));
        } catch (RuntimeException | Error thrown) {
            capture.close();
            if (source != null) source.close();
            throw thrown;
        }
    }

    private void prepareOnWorker(Key key, Preparation request, MinecraftEntityUploader.UploadJob upload,
                                 ReadyMesh<MinecraftProgramTypes.InstanceData> source, long queuedNanos) {
        var event = queuedNanos == 0 ? null : new EntityMeshUploadEvent();
        long cpu = 0, allocated = 0;
        if (event != null) {
            event.keyDomain = key.domain;
            event.keyValue = key.value;
            event.queuedNanos = queuedNanos;
            event.startedNanos = System.nanoTime();
            cpu = UPLOAD_THREADS.getCurrentThreadCpuTime();
            allocated = UPLOAD_THREADS.getCurrentThreadAllocatedBytes();
            event.begin();
        }
        MinecraftEntityUploader.UploadedEntity uploaded = null;
        try (upload; source) {
            if (request.cancelled) return;
            uploaded = upload.finish();
            var future = meshes.prepare(MinecraftProgramTypes.INSTANCE_DATA, uploaded.build(), source);
            var retainedUpload = uploaded;
            uploaded = null;
            future.whenComplete((ready, thrown) -> complete(key, request, new Generation(ready, retainedUpload), thrown));
        } catch (Throwable thrown) {
            if (uploaded != null) uploaded.close();
            complete(key, request, new Generation(null, null), thrown);
        } finally {
            if (event != null) {
                event.finishedNanos = System.nanoTime();
                event.cpuNanos = UPLOAD_THREADS.getCurrentThreadCpuTime() - cpu;
                event.allocatedBytes = UPLOAD_THREADS.getCurrentThreadAllocatedBytes() - allocated;
                event.end();
                event.commit();
            }
        }
    }

    private void complete(Key key, Preparation request, Generation generation, Throwable thrown) {
        long readyNanos = PUBLICATION_EVENT.isEnabled() ? System.nanoTime() : 0L;
        synchronized (submissionLock) {
            if (accepting) {
                try {
                    executePublication(() -> publishPrepared(key, request, generation, thrown, readyNanos));
                } catch (RuntimeException | Error rejected) {
                    failure = cleanup(rejected, generation::close);
                }
                return;
            }
        }
        generation.close();
    }

    /** Completion uses the latest committed placement and never publishes a removed request. */
    private void publishPrepared(Key key, Preparation request, Generation generation, Throwable thrown,
                                 long readyNanos) {
        var resident = residents.get(key);
        if (failure != null || resident == null || resident.request != request) {
            generation.close();
            return;
        }
        try {
            if (thrown != null) throw new IllegalStateException("Entity mesh preparation failed", thrown);
            channel.edit(List.of(new SceneEdit.SetInstance<>(resident.instance, scene, generation.mesh,
                    resident.transform, resident.mask, generation.uploaded.instanceData())));
        } catch (Throwable rejected) {
            generation.close();
            failure = rejected;
            return;
        }
        residents.put(key, new Resident(resident.instance, resident.revision, resident.transform,
                resident.mask, generation, null, resident.queued));
        if (readyNanos != 0L && PUBLICATION_EVENT.isEnabled()) {
            var event = new EntityMeshPublicationEvent();
            event.keyDomain = key.domain;
            event.keyValue = key.value;
            event.readyNanos = readyNanos;
            event.publishedNanos = System.nanoTime();
            event.commit();
        }
        if (resident.live != null) resident.live.close();
        if (request.accepted != null) request.accepted.run();
        startQueued(key);
    }

    @Override public void stop() {
        CompletableFuture<Void> result;
        synchronized (this) {
            if (stopResult == null) {
                if (group != null) throw new IllegalStateException("cannot stop during entity update group");
                stopped = true;
                stopResult = new CompletableFuture<>();
                synchronized (submissionLock) {
                    accepting = false;
                    publicationExecutor.execute(() -> {
                        Throwable failure = null;
                        try {
                            var edits = new ArrayList<SceneEdit>();
                            for (var resident : residents.values()) {
                                if (resident.live != null) edits.add(new SceneEdit.DropInstance(resident.instance));
                            }
                            if (!edits.isEmpty()) channel.edit(edits);
                        } catch (Throwable thrown) { failure = thrown; }
                        for (var resident : residents.values()) {
                            if (resident.request != null) resident.request.cancelled = true;
                            if (resident.queued != null) failure = cleanup(failure, resident.queued::close);
                            if (resident.live != null) failure = cleanup(failure, resident.live::close);
                        }
                        residents.clear();
                        if (failure == null) stopResult.complete(null);
                        else stopResult.completeExceptionally(failure);
                    });
                }
            }
            result = stopResult;
        }
        Throwable failure = cleanup(null, result::join);
        failure = cleanup(failure, () -> CompletableFuture.allOf(packing.toArray(CompletableFuture[]::new)).join());
        if (ownsExecutors) {
            failure = cleanup(failure, ((ExecutorService) publicationExecutor)::close);
            failure = cleanup(failure, ((ExecutorService) packingExecutor)::close);
        }
        throwFailure(failure);
    }

    @Override public void close() {
        Throwable failure = cleanup(null, this::stop);
        synchronized (this) {
            if (!closed) {
                closed = true;
                failure = cleanup(failure, uploader::close);
            }
        }
        throwFailure(failure);
    }

    private static Throwable cleanup(Throwable failure, Runnable action) {
        try { action.run(); }
        catch (Throwable thrown) {
            if (failure == null) return thrown;
            if (failure != thrown) failure.addSuppressed(thrown);
        }
        return failure;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("entity resource cleanup failed", failure);
    }

    private void requireRunning() {
        if (stopped) throw new IllegalStateException("entity geometry is stopped");
        if (failure != null) throw new IllegalStateException("entity publication failed", failure);
    }

    private static void closeCaptures(List<Change> changes) {
        Throwable failure = null;
        for (var change : changes) {
            if (change instanceof Put put) failure = cleanup(failure, put.capture::close);
        }
        throwFailure(failure);
    }

    /** Captures changes until submission, or releases every captured input when abandoned. */
    public final class UpdateGroup implements AutoCloseable {
        private final List<Change> changes = new ArrayList<>();
        private boolean finished;

        private UpdateGroup() { }

        public void submit() {
            synchronized (MinecraftEntityGeometry.this) {
                requireRunning();
                if (finished) throw new IllegalStateException("entity update group is finished");
                MinecraftEntityGeometry.this.submit(List.copyOf(changes));
                finished = true;
                group = null;
            }
        }

        @Override public void close() {
            synchronized (MinecraftEntityGeometry.this) {
                if (finished) return;
                var abandoned = List.copyOf(changes);
                finished = true;
                group = null;
                executePublication(() -> closeCaptures(abandoned));
            }
        }
    }

    public record Key(long domain, long value) { }
    public record MeshRevision(long epoch, long content, long topology) { }
    private sealed interface Change { Key key(); }
    private record Put(Key key, Capture capture, GeometryTransform transform, int mask) implements Change { }
    private record Transform(Key key, GeometryTransform transform, int mask, Runnable accepted) implements Change { }
    private record Drop(Key key) implements Change { }
    /** Pending groups may be superseded together, while removal still gives a later put a new identity. */
    private static final class PendingChanges {
        boolean dropped;
        Put put;
        Transform transform;
        void merge(Change next, List<Capture> discarded) {
            switch (next) {
                case Put value -> {
                    if (put != null) discarded.add(put.capture);
                    put = value;
                    transform = null;
                }
                case Transform value -> transform = value;
                case Drop ignored -> {
                    if (put != null) discarded.add(put.capture);
                    dropped = true;
                    put = null;
                    transform = null;
                }
            }
        }
    }
    private record Resident(InstanceId instance, MeshRevision revision, GeometryTransform transform,
                            int mask, Generation live, Preparation request, Capture queued) { }
    private record Capture(MeshRevision revision, MinecraftEntityUploader.UploadJob upload,
                           Runnable accepted) implements AutoCloseable {
        @Override public void close() { upload.close(); }
    }
    private static final class Preparation {
        final Runnable accepted;
        volatile boolean cancelled;
        Preparation(Runnable accepted) { this.accepted = accepted; }
    }

    @jdk.jfr.Name("dev.comfyfluffy.caustica.EntityMeshUpload")
    @jdk.jfr.Label("Entity worker packing and mesh submission")
    @jdk.jfr.Category({"Caustica", "Geometry"})
    @jdk.jfr.StackTrace(false)
    @jdk.jfr.Enabled(false)
    static final class EntityMeshUploadEvent extends jdk.jfr.Event {
        long keyDomain;
        long keyValue;
        long queuedNanos;
        long startedNanos;
        long finishedNanos;
        @jdk.jfr.Timespan(jdk.jfr.Timespan.NANOSECONDS) long cpuNanos;
        @jdk.jfr.DataAmount(jdk.jfr.DataAmount.BYTES) long allocatedBytes;
    }

    @jdk.jfr.Name("dev.comfyfluffy.caustica.EntityMeshPublication")
    @jdk.jfr.Label("Entity mesh readiness to retained publication")
    @jdk.jfr.Category({"Caustica", "Geometry"})
    @jdk.jfr.StackTrace(false)
    @jdk.jfr.Enabled(false)
    static final class EntityMeshPublicationEvent extends jdk.jfr.Event {
        long keyDomain;
        long keyValue;
        @jdk.jfr.Label("System.nanoTime GPU-ready callback") long readyNanos;
        @jdk.jfr.Label("System.nanoTime retained publication") long publishedNanos;
    }
    private record Generation(ReadyMesh<MinecraftProgramTypes.InstanceData> mesh,
                              MinecraftEntityUploader.UploadedEntity uploaded) implements AutoCloseable {
        @Override public void close() {
            Throwable failure = mesh == null ? null : cleanup(null, mesh::close);
            if (uploaded != null) failure = cleanup(failure, uploaded::close);
            throwFailure(failure);
        }
    }
}
