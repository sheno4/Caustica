package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Retained Minecraft placements and per-resident revision-keyed meshes for one borrowed world scene. */
public final class MinecraftEntityGeometry implements MinecraftWorldSessionContribution {
    private final GeometryChannel channel;
    private final SceneId scene;
    private final MinecraftEntityUploader uploader;
    private final Map<Key, Resident> residents = new LinkedHashMap<>();
    private final Map<PoolKey, SharedMesh> meshes = new LinkedHashMap<>();
    private PendingGroup pendingGroup;
    private boolean stopped;

    public MinecraftEntityGeometry(GeometryChannel channel, SceneId scene, MinecraftEntityUploader uploader) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
    }

    /** Stages independently retired updates so one capture frame becomes one atomic scene publication. */
    public synchronized UpdateGroup beginUpdateGroup() {
        requireRunning();
        if (pendingGroup != null) throw new IllegalStateException("an entity update group is already active");
        pendingGroup = new PendingGroup();
        return pendingGroup;
    }

    /** Atomically installs revision-keyed mesh content and its current rigid placement. */
    public synchronized void put(Key key, MeshRevision revision, MinecraftEntityMesh mesh,
                                 GeometryTransform transform, int mask) {
        requireRunning();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(transform, "transform");
        Resident prior = residents.get(key);
        if (prior != null && prior.mesh.poolKey.revision.equals(revision)
                && prior.transform.equals(transform) && prior.mask == mask) {
            return;
        }
        Resident target = prior != null ? prior : new Resident(channel.newInstance());
        PoolKey poolKey = new PoolKey(key, revision);
        SharedMesh targetMesh = meshes.get(poolKey);
        boolean newMesh = targetMesh == null;
        if (newMesh) {
            MeshId<MinecraftProgramTypes.InstanceData> meshId = channel.newMesh(MinecraftProgramTypes.INSTANCE_DATA);
            MinecraftEntityUploader.UploadedEntity uploaded = uploader.upload(mesh);
            targetMesh = new SharedMesh(poolKey, meshId, new Lease(uploaded));
        }
        Runnable retirement = targetMesh.lease.retain();
        SharedMesh submittedMesh = targetMesh;
        SharedMesh priorMesh = prior != null ? prior.mesh : null;
        boolean staged = false;
        try {
            var operations = new ArrayList<GeometryChannel.Operation>(3);
            if (newMesh) {
                operations.add(new GeometryChannel.SetMesh<>(targetMesh.mesh, targetMesh.lease.uploaded.build()));
            }
            operations.add(new GeometryChannel.SetInstance<>(target.instance, scene, targetMesh.mesh, transform, mask,
                    targetMesh.lease.uploaded.instanceData()));
            if (priorMesh != null && priorMesh != targetMesh && priorMesh.residents == 1) {
                operations.add(new GeometryChannel.DropMesh<>(priorMesh.mesh));
            }
            submit(new RetainedBatch<>(operations, retirement), failure -> {
                if (newMesh) submittedMesh.lease.reject(failure);
                else submittedMesh.lease.cancelRetain(failure);
            });
            staged = true;
        } catch (RuntimeException | Error failure) {
            if (!staged) {
                if (newMesh) targetMesh.lease.reject(failure);
                else targetMesh.lease.cancelRetain(failure);
            }
            throw failure;
        }
        if (priorMesh != targetMesh) {
            targetMesh.residents++;
            if (priorMesh != null && --priorMesh.residents == 0) meshes.remove(priorMesh.poolKey);
        }
        if (newMesh) meshes.put(poolKey, targetMesh);
        target.mesh = targetMesh;
        target.transform = transform;
        target.mask = mask;
        residents.put(key, target);
    }

    /** Replaces only the placement so the renderer derives rigid previous/current transforms. */
    public synchronized void transform(Key key, GeometryTransform transform, int mask) {
        requireRunning();
        Resident resident = residents.get(Objects.requireNonNull(key, "key"));
        if (resident == null) return;
        Objects.requireNonNull(transform, "transform");
        if (resident.transform.equals(transform) && resident.mask == mask) return;
        Runnable retirement = resident.mesh.lease.retain();
        boolean staged = false;
        try {
            submit(new RetainedBatch<>(java.util.List.of(new GeometryChannel.SetInstance<>(
                    resident.instance, scene, resident.mesh.mesh, transform, mask,
                    resident.mesh.lease.uploaded.instanceData())), retirement),
                    resident.mesh.lease::cancelRetain);
            staged = true;
        } catch (RuntimeException | Error failure) {
            if (!staged) resident.mesh.lease.cancelRetain(failure);
            throw failure;
        }
        resident.transform = transform;
        resident.mask = mask;
    }

    /** Atomically removes the placement and mesh for one logical Minecraft object. */
    public synchronized void drop(Key key) {
        requireRunning();
        Objects.requireNonNull(key, "key");
        Resident resident = residents.get(key);
        if (resident == null) return;
        var operations = new ArrayList<GeometryChannel.Operation>(2);
        operations.add(new GeometryChannel.DropInstance(resident.instance));
        if (resident.mesh.residents == 1) operations.add(new GeometryChannel.DropMesh<>(resident.mesh.mesh));
        submit(RetainedBatch.of(operations), failure -> { });
        residents.remove(key);
        if (--resident.mesh.residents == 0) meshes.remove(resident.mesh.poolKey);
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        if (pendingGroup != null) throw new IllegalStateException("cannot stop during an entity update group");
        if (!residents.isEmpty()) {
            var operations = new ArrayList<GeometryChannel.Operation>(residents.size() + meshes.size());
            for (Resident resident : residents.values()) {
                operations.add(new GeometryChannel.DropInstance(resident.instance));
            }
            for (SharedMesh mesh : meshes.values()) operations.add(new GeometryChannel.DropMesh<>(mesh.mesh));
            channel.submit(RetainedBatch.of(operations));
        }
        residents.clear();
        meshes.clear();
        stopped = true;
    }

    @Override public void close() {
        uploader.close();
    }

    private void requireRunning() {
        if (stopped) throw new IllegalStateException("entity geometry is stopped");
    }

    private void submit(RetainedBatch<GeometryChannel.Operation> batch,
                        java.util.function.Consumer<Throwable> rejected) {
        if (pendingGroup != null) {
            pendingGroup.batches.add(batch);
            pendingGroup.rejections.add(rejected);
        } else {
            channel.submit(batch);
        }
    }

    public interface UpdateGroup extends AutoCloseable {
        GeometryPublication submit();
        @Override void close();
    }

    private final class PendingGroup implements UpdateGroup {
        final Map<Key, ResidentSnapshot> residentSnapshot = new LinkedHashMap<>();
        final Map<PoolKey, SharedMesh> meshSnapshot = new LinkedHashMap<>(meshes);
        final Map<SharedMesh, Integer> meshResidentCounts = new IdentityHashMap<>();
        final List<RetainedBatch<GeometryChannel.Operation>> batches = new ArrayList<>();
        final List<java.util.function.Consumer<Throwable>> rejections = new ArrayList<>();
        boolean finished;

        PendingGroup() {
            residents.forEach((key, resident) -> residentSnapshot.put(key,
                    new ResidentSnapshot(resident.instance, resident.mesh, resident.transform, resident.mask)));
            meshes.values().forEach(mesh -> meshResidentCounts.put(mesh, mesh.residents));
        }

        @Override public GeometryPublication submit() {
            synchronized (MinecraftEntityGeometry.this) {
                requireActive();
                try {
                    GeometryPublication publication = batches.isEmpty()
                            ? GeometryPublication.alreadyVisible()
                            : channel.submitGroup(batches);
                    finished = true;
                    pendingGroup = null;
                    return publication;
                } catch (RuntimeException | Error failure) {
                    rollback(failure);
                    throw failure;
                }
            }
        }

        @Override public void close() {
            synchronized (MinecraftEntityGeometry.this) {
                if (finished) return;
                requireActive();
                rollback(new IllegalStateException("entity update group was cancelled"));
            }
        }

        private void rollback(Throwable failure) {
            for (int index = rejections.size() - 1; index >= 0; index--) {
                rejections.get(index).accept(failure);
            }
            meshResidentCounts.forEach((mesh, count) -> mesh.residents = count);
            meshes.clear();
            meshes.putAll(meshSnapshot);
            residents.clear();
            residentSnapshot.forEach((key, snapshot) -> residents.put(key, snapshot.restore()));
            finished = true;
            pendingGroup = null;
        }

        private void requireActive() {
            if (finished || pendingGroup != this) throw new IllegalStateException("entity update group is not active");
        }
    }

    private record ResidentSnapshot(InstanceId instance, SharedMesh mesh,
                                    GeometryTransform transform, int mask) {
        Resident restore() {
            Resident resident = new Resident(instance);
            resident.mesh = mesh;
            resident.transform = transform;
            resident.mask = mask;
            return resident;
        }
    }

    public record Key(long domain, long value) { }

    /** Stable captured content identity used to reuse one resident's unchanged retained BLAS. */
    public record MeshRevision(long epoch, long content, long topology) { }

    private record PoolKey(Key resident, MeshRevision revision) { }

    private static final class Resident {
        final InstanceId instance;
        SharedMesh mesh;
        GeometryTransform transform;
        int mask;

        Resident(InstanceId instance) {
            this.instance = instance;
        }
    }

    private static final class SharedMesh {
        final PoolKey poolKey;
        final MeshId<MinecraftProgramTypes.InstanceData> mesh;
        final Lease lease;
        int residents;

        SharedMesh(PoolKey poolKey, MeshId<MinecraftProgramTypes.InstanceData> mesh, Lease lease) {
            this.poolKey = poolKey;
            this.mesh = mesh;
            this.lease = lease;
        }
    }

    /** One uploaded allocation may be retained by overlapping mesh and transform replacement batches. */
    private static final class Lease {
        final MinecraftEntityUploader.UploadedEntity uploaded;
        int references;

        Lease(MinecraftEntityUploader.UploadedEntity uploaded) {
            this.uploaded = uploaded;
        }

        synchronized Runnable retain() {
            references++;
            return this::release;
        }

        synchronized void cancelRetain(Throwable failure) {
            references--;
            if (references == 0) close(failure);
        }

        synchronized void reject(Throwable failure) {
            references--;
            if (references == 0) close(failure);
        }

        private synchronized void release() {
            if (--references == 0) {
                try {
                    uploaded.close();
                } catch (Throwable ignored) {
                    // Retained retirement must finish without escaping into the renderer.
                }
            }
        }

        private void close(Throwable failure) {
            try {
                uploaded.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
