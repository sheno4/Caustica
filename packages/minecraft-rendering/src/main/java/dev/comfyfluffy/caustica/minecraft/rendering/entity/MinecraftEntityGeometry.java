package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import java.util.*;

/** Keeps each live mesh visible while its replacement prepares, with independent rigid placement edits. */
public final class MinecraftEntityGeometry implements MinecraftWorldSessionContribution {
    private final MeshPreparer meshes;
    private final SceneChannel channel;
    private final SceneId scene;
    private final MinecraftEntityUploader uploader;
    private final Map<Key, Resident> residents = new LinkedHashMap<>();
    private final ArrayDeque<Completion> completed = new ArrayDeque<>();
    private PendingGroup group;
    private boolean stopped;

    public MinecraftEntityGeometry(MeshPreparer meshes, SceneChannel channel, SceneId scene,
                                   MinecraftEntityUploader uploader) {
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
        var uploaded = uploader.upload(capture.mesh);
        java.util.concurrent.CompletableFuture<ReadyMesh<MinecraftProgramTypes.InstanceData>> future;
        try {
            future = meshes.prepare(MinecraftProgramTypes.INSTANCE_DATA, uploaded.build(),
                    resident.live == null ? null : resident.live.mesh);
        } catch (RuntimeException | Error failure) {
            uploaded.close();
            throw failure;
        }
        residents.put(key, new Resident(resident.instance, capture.revision, resident.transform, resident.mask,
                resident.live, request, null));
        future.whenComplete((ready, failure) -> {
            synchronized (MinecraftEntityGeometry.this) {
                var result = new Generation(ready, uploaded);
                if (stopped) result.close();
                else completed.add(new Completion(key, request, result, failure));
            }
        });
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

    @Override public synchronized void stop() {
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
    }

    @Override public synchronized void close() { stop(); uploader.close(); }

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
    private record Completion(Key key, Preparation request, Generation generation, Throwable failure) { }
    private record Generation(ReadyMesh<MinecraftProgramTypes.InstanceData> mesh,
                              MinecraftEntityUploader.UploadedEntity uploaded) implements AutoCloseable {
        @Override public void close() { if (mesh != null) mesh.close(); uploaded.close(); }
    }
}
