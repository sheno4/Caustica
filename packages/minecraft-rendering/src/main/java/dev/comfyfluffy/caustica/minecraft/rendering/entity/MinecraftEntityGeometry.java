package dev.comfyfluffy.caustica.minecraft.rendering.entity;

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

/** Retained Minecraft placements and per-resident meshes for one borrowed world scene. */
public final class MinecraftEntityGeometry implements MinecraftWorldSessionContribution {
    private final GeometryChannel channel;
    private final SceneId scene;
    private final MinecraftEntityUploader uploader;
    private final Map<Key, Resident> residents = new LinkedHashMap<>();
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
        if (prior != null && prior.revision.equals(revision)) {
            submitLatest(prior, transform, mask);
            prior.transform = transform;
            prior.mask = mask;
            return;
        }
        Resident target = prior != null ? prior : new Resident(channel.newInstance(),
                channel.newMesh(MinecraftProgramTypes.INSTANCE_DATA));
        MinecraftEntityUploader.UploadedEntity uploaded = uploader.upload(mesh);
        Lease lease = new Lease(uploaded);
        Runnable retirement = lease.retain();
        boolean staged = false;
        try {
            var operations = new ArrayList<GeometryChannel.Operation>(2);
            operations.add(new GeometryChannel.SetMesh<>(target.mesh, uploaded.build()));
            operations.add(new GeometryChannel.SetInstance<>(target.instance, scene, target.mesh, transform, mask,
                    uploaded.instanceData()));
            submit(new RetainedBatch<>(operations, retirement), lease::reject);
            staged = true;
            if (prior != null) submitLatest(target, transform, mask);
        } catch (RuntimeException | Error failure) {
            if (!staged) lease.reject(failure);
            throw failure;
        }
        target.revision = revision;
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
        submitLatest(resident, transform, mask);
        resident.transform = transform;
        resident.mask = mask;
    }

    private void submitLatest(Resident resident, GeometryTransform transform, int mask) {
        GeometryChannel.LatestInstance latest = new GeometryChannel.LatestInstance(
                resident.instance, Objects.requireNonNull(transform, "transform"), mask);
        if (pendingGroup != null) pendingGroup.latestInstances.put(resident.instance, latest);
        else channel.submitGroupWithLatest(List.of(), List.of(latest));
    }

    /** Atomically removes the placement and mesh for one logical Minecraft object. */
    public synchronized void drop(Key key) {
        requireRunning();
        Objects.requireNonNull(key, "key");
        Resident resident = residents.get(key);
        if (resident == null) return;
        var operations = new ArrayList<GeometryChannel.Operation>(2);
        operations.add(new GeometryChannel.DropInstance(resident.instance));
        operations.add(new GeometryChannel.DropMesh<>(resident.mesh));
        submit(RetainedBatch.of(operations), failure -> { });
        residents.remove(key);
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        if (pendingGroup != null) throw new IllegalStateException("cannot stop during an entity update group");
        if (!residents.isEmpty()) {
            var operations = new ArrayList<GeometryChannel.Operation>(residents.size() * 2);
            for (Resident resident : residents.values()) {
                operations.add(new GeometryChannel.DropInstance(resident.instance));
                operations.add(new GeometryChannel.DropMesh<>(resident.mesh));
            }
            channel.submit(RetainedBatch.of(operations));
        }
        residents.clear();
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
        final List<RetainedBatch<GeometryChannel.Operation>> batches = new ArrayList<>();
        final List<java.util.function.Consumer<Throwable>> rejections = new ArrayList<>();
        final Map<InstanceId, GeometryChannel.LatestInstance> latestInstances = new IdentityHashMap<>();
        boolean finished;

        PendingGroup() {
            residents.forEach((key, resident) -> residentSnapshot.put(key,
                    new ResidentSnapshot(resident.instance, resident.mesh, resident.revision,
                            resident.transform, resident.mask)));
        }

        @Override public GeometryPublication submit() {
            synchronized (MinecraftEntityGeometry.this) {
                requireActive();
                try {
                    GeometryPublication publication = batches.isEmpty() && latestInstances.isEmpty()
                            ? GeometryPublication.alreadyVisible()
                            : channel.submitGroupWithLatest(batches, List.copyOf(latestInstances.values()));
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
            residents.clear();
            residentSnapshot.forEach((key, snapshot) -> residents.put(key, snapshot.restore()));
            finished = true;
            pendingGroup = null;
        }

        private void requireActive() {
            if (finished || pendingGroup != this) throw new IllegalStateException("entity update group is not active");
        }
    }

    private record ResidentSnapshot(InstanceId instance, MeshId<MinecraftProgramTypes.InstanceData> mesh,
                                    MeshRevision revision, GeometryTransform transform, int mask) {
        Resident restore() {
            Resident resident = new Resident(instance, mesh);
            resident.revision = revision;
            resident.transform = transform;
            resident.mask = mask;
            return resident;
        }
    }

    public record Key(long domain, long value) { }

    /** Stable captured content identity used to reuse one resident's unchanged retained BLAS. */
    public record MeshRevision(long epoch, long content, long topology) { }

    private static final class Resident {
        final InstanceId instance;
        final MeshId<MinecraftProgramTypes.InstanceData> mesh;
        MeshRevision revision;
        GeometryTransform transform;
        int mask;

        Resident(InstanceId instance, MeshId<MinecraftProgramTypes.InstanceData> mesh) {
            this.instance = instance;
            this.mesh = mesh;
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
