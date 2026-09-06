package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import java.util.*;
import java.util.concurrent.*;

/** Keeps each live mesh visible while its replacement prepares, with independent rigid placement edits. */
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
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Set<CompletableFuture<Void>> packing = new HashSet<>();
    private final Map<Key, Resident> residents = new LinkedHashMap<>();
    private final ArrayDeque<Completion> completed = new ArrayDeque<>();
    private PendingGroup group;
    private boolean stopped;

    public MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                                   MinecraftEntityUploader uploader) {
        this(meshes, channel, scene, uploader, Executors.newFixedThreadPool(2,
                Thread.ofPlatform().daemon().name("caustica-entity-upload-", 0).factory()), true);
    }

    MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                            MinecraftEntityUploader uploader, Executor executor) {
        this(meshes, channel, scene, uploader, executor, false);
    }

    private MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                                    MinecraftEntityUploader uploader, Executor executor, boolean ownedExecutor) {
        this.executor = executor;
        this.ownedExecutor = ownedExecutor ? (ExecutorService) executor : null;
        this.meshes = meshes;
        this.channel = channel;
        this.scene = scene;
        this.uploader = uploader;
    }

    public synchronized UpdateGroup beginUpdateGroup() {
        requireRunning();
        if (group != null) throw new IllegalStateException("entity update group already active");
        publishPrepared();
        group = new PendingGroup();
        return group;
    }

    public synchronized void put(Key key, MeshRevision revision, MinecraftEntityMesh mesh,
                                 GeometryTransform transform, int mask) {
        put(key, revision, mesh, transform, mask, null);
    }

    public synchronized void put(Key key, MeshRevision revision, MinecraftEntityMesh mesh,
                                 GeometryTransform transform, int mask, Runnable accepted) {
        requireRunning();
        var prior = residents.get(key);
        if (prior != null && prior.revision.equals(revision)) {
            transform(key, transform, mask);
            return;
        }
        var instance = prior == null ? channel.newInstance() : prior.instance;
        if (prior != null && prior.live != null) emit(new SceneEdit.SetTransform(instance, transform, mask));
        var capture = new Capture(revision, mesh, accepted);
        var target = new Resident(instance, revision, transform, mask,
                prior == null ? null : prior.live, prior == null ? null : prior.request, null);
        if (target.request != null) {
            residents.put(key, new Resident(instance, revision, transform, mask, target.live, target.request, capture));
        } else {
            startPreparation(key, target, capture);
        }
    }

    /** One running build and one newest CPU capture bound work without starving deforming entities. */
    private void startPreparation(Key key, Resident resident, Capture capture) {
        var request = new Preparation(capture.accepted);
        var upload = uploader.prepareUpload(capture.mesh);
        ReadyMesh<MinecraftProgramTypes.InstanceData> source;
        try { source = resident.live == null ? null : resident.live.mesh.retain(); }
        catch (RuntimeException | Error failure) { upload.close(); throw failure; }
        var previous = residents.get(key);
        residents.put(key, new Resident(resident.instance, capture.revision, resident.transform, resident.mask,
                resident.live, request, null));
        var terminal = new CompletableFuture<Void>();
        packing.add(terminal);
        long queuedNanos = UPLOAD_EVENT.isEnabled() ? System.nanoTime() : 0L;
        try {
            executor.execute(() -> {
                try { prepareOnWorker(key, request, upload, source, queuedNanos); }
                finally {
                    synchronized (MinecraftEntityGeometry.this) { packing.remove(terminal); }
                    terminal.complete(null);
                }
            });
        } catch (RuntimeException | Error failure) {
            packing.remove(terminal);
            if (previous == null) residents.remove(key); else residents.put(key, previous);
            upload.close();
            if (source != null) source.close();
            throw failure;
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
            synchronized (this) {
                var resident = residents.get(key);
                if (stopped || resident == null || resident.request != request) return;
            }
            uploaded = upload.finish();
            var future = meshes.prepare(MinecraftProgramTypes.INSTANCE_DATA, uploaded.build(), source);
            var retainedUpload = uploaded;
            uploaded = null;
            future.whenComplete((ready, failure) -> complete(key, request, new Generation(ready, retainedUpload), failure));
        } catch (Throwable failure) {
            if (uploaded != null) uploaded.close();
            complete(key, request, new Generation(null, null), failure);
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

    private void complete(Key key, Preparation request, Generation generation, Throwable failure) {
        long readyNanos = PUBLICATION_EVENT.isEnabled() ? System.nanoTime() : 0L;
        synchronized (this) {
            if (stopped) generation.close();
            else completed.add(new Completion(key, request, generation, failure, readyNanos));
        }
    }

    public synchronized void transform(Key key, GeometryTransform transform, int mask) {
        requireRunning();
        var resident = residents.get(key);
        if (resident == null) return;
        if (resident.live != null) emit(new SceneEdit.SetTransform(resident.instance, transform, mask));
        residents.put(key, new Resident(resident.instance, resident.revision, transform, mask,
                resident.live, resident.request, resident.queued));
    }

    public synchronized void drop(Key key) {
        requireRunning();
        var resident = residents.get(key);
        if (resident == null) return;
        if (resident.live != null) {
            emit(new SceneEdit.DropInstance(resident.instance));
            retire(resident.live);
        }
        residents.remove(key);
    }

    /** Completion publication uses the most recent transform, never the one captured by preparation. */
    private void publishPrepared() {
        Completion completion;
        while ((completion = completed.poll()) != null) {
            var resident = residents.get(completion.key);
            if (resident == null || resident.request != completion.request) {
                completion.generation.close();
                continue;
            }
            if (completion.failure != null) {
                completion.generation.close();
                throw new IllegalStateException("Entity mesh preparation failed", completion.failure);
            }
            var generation = completion.generation;
            try {
                channel.edit(List.of(new SceneEdit.SetInstance<>(resident.instance, scene, generation.mesh,
                        resident.transform, resident.mask, generation.uploaded.instanceData())));
            } catch (RuntimeException | Error failure) {
                generation.close();
                throw failure;
            }
            if (completion.readyNanos != 0L && PUBLICATION_EVENT.isEnabled()) {
                var event = new EntityMeshPublicationEvent();
                event.keyDomain = completion.key.domain;
                event.keyValue = completion.key.value;
                event.readyNanos = completion.readyNanos;
                event.publishedNanos = System.nanoTime();
                event.commit();
            }
            var published = new Resident(resident.instance, resident.revision,
                    resident.transform, resident.mask, generation, null, null);
            residents.put(completion.key, published);
            if (resident.live != null) resident.live.close();
            if (completion.request.accepted != null) completion.request.accepted.run();
            if (resident.queued != null) startPreparation(completion.key, published, resident.queued);
        }
    }

    private void emit(SceneEdit edit) {
        if (group != null) group.edits.add(edit);
        else channel.edit(List.of(edit));
    }

    private void retire(Generation generation) {
        if (group != null) group.displaced.add(generation);
        else generation.close();
    }

    @Override public void stop() {
        CompletableFuture<?>[] pending;
        synchronized (this) {
            if (stopped) return;
            if (group != null) throw new IllegalStateException("cannot stop during entity update group");
            var edits = new ArrayList<SceneEdit>();
            for (var resident : residents.values()) {
                if (resident.live != null) edits.add(new SceneEdit.DropInstance(resident.instance));
            }
            if (!edits.isEmpty()) channel.edit(edits);
            stopped = true;
            residents.values().forEach(resident -> { if (resident.live != null) resident.live.close(); });
            residents.clear();
            completed.forEach(completion -> completion.generation.close());
            completed.clear();
            pending = packing.toArray(CompletableFuture[]::new);
        }
        CompletableFuture.allOf(pending).join();
        if (ownedExecutor != null) ownedExecutor.close();
    }

    @Override public void close() { stop(); uploader.close(); }

    private void requireRunning() {
        if (stopped) throw new IllegalStateException("entity geometry is stopped");
    }

    public interface UpdateGroup extends AutoCloseable {
        void submit();
        @Override void close();
    }

    private final class PendingGroup implements UpdateGroup {
        final Map<Key, Resident> snapshot = new LinkedHashMap<>(residents);
        final List<SceneEdit> edits = new ArrayList<>();
        final List<Generation> displaced = new ArrayList<>();
        boolean finished;

        @Override public void submit() {
            synchronized (MinecraftEntityGeometry.this) {
                if (!edits.isEmpty()) channel.edit(edits);
                displaced.forEach(Generation::close);
                finished = true;
                group = null;
            }
        }

        @Override public void close() {
            synchronized (MinecraftEntityGeometry.this) {
                if (finished) return;
                residents.clear();
                residents.putAll(snapshot);
                finished = true;
                group = null;
                completed.removeIf(completion -> {
                    var resident = residents.get(completion.key);
                    boolean stale = resident == null || resident.request != completion.request;
                    if (stale) completion.generation.close();
                    return stale;
                });
            }
        }
    }

    public record Key(long domain, long value) { }
    public record MeshRevision(long epoch, long content, long topology) { }

    private record Resident(InstanceId instance, MeshRevision revision, GeometryTransform transform,
                            int mask, Generation live, Preparation request, Capture queued) { }
    private record Capture(MeshRevision revision, MinecraftEntityMesh mesh, Runnable accepted) { }
    private record Preparation(Runnable accepted) { }
    private record Completion(Key key, Preparation request, Generation generation, Throwable failure,
                              long readyNanos) { }

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
        @Override public void close() { if (mesh != null) mesh.close(); if (uploaded != null) uploaded.close(); }
    }
}
