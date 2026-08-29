package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Direct retained-geometry owner for Minecraft entities in one borrowed world scene. */
public final class MinecraftEntityGeometry implements MinecraftWorldSessionContribution {
    private final GeometryChannel channel;
    private final SceneId scene;
    private final MinecraftEntityUploader uploader;
    private final Map<Key, Resident> residents = new LinkedHashMap<>();
    private boolean stopped;

    public MinecraftEntityGeometry(GeometryChannel channel, SceneId scene, MinecraftEntityUploader uploader) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
    }

    /** Atomically installs a mesh and its current rigid placement. */
    public synchronized void put(Key key, MinecraftEntityMesh mesh, GeometryTransform transform, int mask) {
        requireRunning();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(transform, "transform");
        Resident prior = residents.get(key);
        Resident target = prior;
        if (target == null) {
            target = new Resident(channel.newMesh(MinecraftProgramTypes.INSTANCE_DATA), channel.newInstance());
        }
        MinecraftEntityUploader.UploadedEntity uploaded = uploader.upload(mesh);
        Lease lease = new Lease(uploaded);
        Runnable retirement = lease.retain();
        try {
            channel.submit(new RetainedBatch<>(java.util.List.of(
                    new GeometryChannel.SetMesh<>(target.mesh, uploaded.build()),
                    new GeometryChannel.SetInstance<>(target.instance, scene, target.mesh, transform, mask,
                            uploaded.instanceData())), retirement));
        } catch (RuntimeException | Error failure) {
            lease.reject(failure);
            throw failure;
        }
        target.lease = lease;
        target.transform = transform;
        target.mask = mask;
        residents.put(key, target);
    }

    /** Replaces only the placement so the renderer derives rigid previous/current transforms. */
    public synchronized void transform(Key key, GeometryTransform transform, int mask) {
        requireRunning();
        Resident resident = residents.get(Objects.requireNonNull(key, "key"));
        if (resident == null) return;
        Runnable retirement = resident.lease.retain();
        try {
            channel.submit(new RetainedBatch<>(java.util.List.of(new GeometryChannel.SetInstance<>(
                    resident.instance, scene, resident.mesh, transform, mask,
                    resident.lease.uploaded.instanceData())), retirement));
        } catch (RuntimeException | Error failure) {
            resident.lease.cancelRetain(failure);
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
        channel.submit(RetainedBatch.of(java.util.List.of(
                new GeometryChannel.DropInstance(resident.instance),
                new GeometryChannel.DropMesh<>(resident.mesh))));
        residents.remove(key);
    }

    @Override public synchronized void stop() {
        if (stopped) return;
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

    public record Key(long domain, long value) { }

    private static final class Resident {
        final MeshId<MinecraftProgramTypes.InstanceData> mesh;
        final InstanceId instance;
        Lease lease;
        GeometryTransform transform;
        int mask;

        Resident(MeshId<MinecraftProgramTypes.InstanceData> mesh, InstanceId instance) {
            this.mesh = mesh;
            this.instance = instance;
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
