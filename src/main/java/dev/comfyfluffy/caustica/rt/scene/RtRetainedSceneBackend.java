package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.GpuBuffer;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.TlasBuilder;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Vulkan owner for immutable retained-scene publications. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final GpuContext ctx;
    private final Map<SceneId, TlasBuilder.Ring> tlasRings = new IdentityHashMap<>();
    private final ArrayDeque<Publication> queued = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<CompletedBuild> completed = new ConcurrentLinkedQueue<>();
    private NativeSnapshot published;
    private Throwable fatalFailure;
    private boolean closed;

    public RtRetainedSceneBackend(GpuContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Accepts a complete logical version. GPU publication remains ordered and atomic. */
    @Override
    public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable previousRetired) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(previousRetired, "previousRetired");
        long tailRevision = queued.isEmpty()
                ? published != null ? published.revision : -1L
                : queued.getLast().snapshot.revision();
        if (snapshot.revision() <= tailRevision) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
        Publication publication = new Publication(snapshot, previousRetired);
        Candidate predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        try {
            publication.candidate = prepare(snapshot, predecessor);
            accept(publication);
            queued.addLast(publication);
        } catch (Throwable failure) {
            if (publication.candidate != null) publication.candidate.releaseRejected();
            throw failure;
        }
    }

    /** Publishes completed snapshots in accepted revision order. */
    public synchronized void progress() {
        requireOpen();
        CompletedBuild terminal;
        while ((terminal = completed.poll()) != null) terminal.publication.complete(terminal.failure);
        while (true) {
            Publication head = queued.peekFirst();
            if (head == null || !head.completed) return;
            finishBuild(head);
        }
    }

    public synchronized long publishedRevision() {
        requireOpen();
        return published == null ? -1L : published.revision;
    }

    /** Immutable light/environment view for one target scene. */
    public synchronized SceneContent content(SceneId scene) {
        NativeSnapshot current = requirePublishedScene(scene);
        return current.content.get(scene);
    }

    /** Prepares only the TLAS belonging to {@code scene}; no implicit root-scene global is used. */
    public synchronized TlasBuilder.Prepared prepareTlas(SceneId scene, SceneOrigin origin, GraphicsUse graphicsUse) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        NativeSnapshot current = requirePublishedScene(scene);
        List<NativeInstance> source = current.instances.get(scene);
        TlasBuilder.InstanceBatch instances = new TlasBuilder.InstanceBatch();
        instances.reset(source.size());
        for (NativeInstance instance : source) {
            float[] transform = instance.logical.transform().relativeTo(origin.x(), origin.y(), origin.z());
            instances.append(transform, 0, 0, 0, instance.mesh.accel.deviceAddress,
                    instance.geometryBase, instance.logical.mask(), instance.sbtRecordOffset);
        }
        current.graphicsUse.mark(graphicsUse);
        TlasBuilder.Ring ring = tlasRings.computeIfAbsent(scene, ignored -> new TlasBuilder.Ring());
        return TlasBuilder.prepare(ctx, instances, ring, graphicsUse);
    }

    /** Packs the GeometryIndex-addressed records for one scene in the same order as its TLAS hit bases. */
    public synchronized ByteBuffer geometryRecords(SceneId scene, SceneOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        NativeSnapshot current = requirePublishedScene(scene);
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (NativeInstance instance : current.instances.get(scene)) {
            records.addAll(RtRetainedGeometryPlan.records(instance.mesh.logical, instance.logical,
                    instance.previousTransform));
        }
        return RtRetainedGeometryPlan.pack(records, origin);
    }

    /** Hit-group handle selection in exact {@code geometry * rayType} SBT order. */
    public synchronized List<RtRetainedGeometryPlan.HitGroup> hitGroups(SceneId scene) {
        NativeSnapshot current = requirePublishedScene(scene);
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (NativeInstance instance : current.instances.get(scene)) {
            records.addAll(RtRetainedGeometryPlan.records(instance.mesh.logical, instance.logical,
                    instance.previousTransform));
        }
        return RtRetainedGeometryPlan.hitGroups(records);
    }

    /** Releases all native state after the GPU executor has stopped and the device has been made idle. */
    public synchronized void shutdownAfterDeviceIdle() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        if (published != null) {
            try {
                published.release();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            published = null;
        }
        while (!queued.isEmpty()) {
            Publication publication = queued.removeFirst();
            try {
                publication.candidate.releaseAfterDeviceIdle();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
            try {
                publication.retirePrevious(null);
            } catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
        }
        for (TlasBuilder.Ring ring : tlasRings.values()) {
            try {
                ring.destroy();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
        }
        tlasRings.clear();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("retained scene shutdown failed", failure);
    }

    private Candidate prepare(RetainedSceneSnapshot snapshot, Candidate predecessor) {
        Map<Long, NativeMesh> meshes = new LinkedHashMap<>();
        List<NativeMesh> created = new ArrayList<>();
        List<RtAccel.PreparedBlas> builds = new ArrayList<>();
        try {
            for (RetainedSceneSnapshot.Mesh mesh : snapshot.meshes()) {
                NativeMesh reused = predecessor == null ? null : predecessor.meshes.get(mesh.identity());
                if (reused != null && RtRetainedGeometryPlan.canReuseBlas(reused.logical.build(), mesh.build())) {
                    reused.retain();
                    reused = reused.withLogical(mesh);
                    meshes.put(mesh.identity(), reused);
                    continue;
                }
                NativeMesh nativeMesh = prepareMesh(mesh);
                created.add(nativeMesh);
                meshes.put(mesh.identity(), nativeMesh);
                builds.add(nativeMesh.buildOperation);
            }
            Candidate candidate = assemble(snapshot, meshes, predecessor);
            candidate.unsubmitted = created;
            candidate.builds = List.copyOf(builds);
            return candidate;
        } catch (Throwable failure) {
            for (NativeMesh mesh : meshes.values()) {
                if (created.contains(mesh)) RtAccel.releaseTransientBlas(mesh.buildOperation);
                else mesh.release();
            }
            throw failure;
        }
    }

    private void accept(Publication publication) {
        Candidate candidate = publication.candidate;
        if (candidate.builds.isEmpty()) {
            candidate.accepted = true;
            candidate.unsubmitted = List.of();
            completed.add(new CompletedBuild(publication, null));
            return;
        }
        ctx.gpuExecutor().submit(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, candidate.builds),
                () -> {
                    RtAccel.freeBlasScratch(candidate.builds);
                    candidate.buildResourcesReleased = true;
                },
                (ignored, failure) -> completed.add(new CompletedBuild(publication, failure)));
        candidate.accepted = true;
        candidate.unsubmitted = List.of();
    }

    private NativeMesh prepareMesh(RetainedSceneSnapshot.Mesh mesh) {
        MeshBuild<?> build = mesh.build();
        RtAccel.PersistentBuild nativeBuild = RtAccel.preparePersistentBlasBuild(ctx,
                build.positions().deviceAddress(), build.positions().byteStride(), build.vertexCount(),
                build.indices().deviceAddress(),
                RtRetainedGeometryPlan.blasRanges(build), "retained mesh " + mesh.identity());
        return new NativeMesh(mesh, nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
    }

    private Candidate assemble(RetainedSceneSnapshot snapshot, Map<Long, NativeMesh> meshes,
                               Candidate predecessor) {
        Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
        Map<SceneId, MutableSceneContent> mutableContent = new IdentityHashMap<>();
        Map<Long, NativeInstance> previousInstances = new LinkedHashMap<>();
        if (predecessor != null) {
            predecessor.instances.values().forEach(values -> values.forEach(
                    instance -> previousInstances.put(instance.logical.identity(), instance)));
        }
        Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
        for (RetainedSceneSnapshot.Scene scene : snapshot.scenes()) {
            instances.put(scene.id(), new ArrayList<>());
            mutableContent.put(scene.id(), new MutableSceneContent(scene.environment()));
            geometryBases.put(scene.id(), 0);
        }
        for (RetainedSceneSnapshot.Instance instance : snapshot.instances()) {
            NativeMesh mesh = meshes.get(instance.meshIdentity());
            int geometryBase = geometryBases.get(instance.scene());
            NativeInstance previous = previousInstances.get(instance.identity());
            instances.get(instance.scene()).add(new NativeInstance(instance, mesh,
                    previous == null ? instance.transform() : previous.logical.transform(), geometryBase,
                    Math.multiplyExact(geometryBase, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY)));
            geometryBases.put(instance.scene(), Math.addExact(geometryBase,
                    mesh.logical.build().geometries().size()));
        }
        for (RetainedSceneSnapshot.Light light : snapshot.lights()) {
            mutableContent.get(light.scene()).lights.add(light.descriptor());
        }
        Map<SceneId, SceneContent> content = new IdentityHashMap<>();
        mutableContent.forEach((scene, value) -> content.put(scene,
                new SceneContent(value.environment, List.copyOf(value.lights))));
        instances.replaceAll((ignored, value) -> List.copyOf(value));
        return new Candidate(snapshot.revision(), meshes, instances, content);
    }

    private void finishBuild(Publication publication) {
        if (queued.getFirst() != publication) {
            throw new IllegalStateException("retained scene publication order changed");
        }
        if (publication.failure != null) {
            fatalFailure = publication.failure;
            throw fatalException();
        }
        queued.removeFirst();
        NativeSnapshot previous = published;
        published = publication.candidate.publish();
        if (previous == null) {
            publication.retirePrevious(null);
        } else {
            ctx.gpuExecutor().retireAfterGraphics(previous.graphicsUse,
                    () -> publication.retirePrevious(previous::release));
        }
    }

    private NativeSnapshot requirePublishedScene(SceneId scene) {
        requireOpen();
        NativeSnapshot current = published;
        if (current == null || !current.content.containsKey(scene)) {
            throw new IllegalArgumentException("scene is not in the published native snapshot");
        }
        return current;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
    }

    private IllegalStateException fatalException() {
        return new IllegalStateException("accepted retained scene GPU work failed", fatalFailure);
    }

    public record SceneContent(EnvironmentBinding<?> environment, List<LightDescriptor> lights) {
        public SceneContent {
            lights = List.copyOf(lights);
        }
    }

    private static final class MutableSceneContent {
        final EnvironmentBinding<?> environment;
        final List<LightDescriptor> lights = new ArrayList<>();
        MutableSceneContent(EnvironmentBinding<?> environment) { this.environment = environment; }
    }

    private static final class Publication {
        final RetainedSceneSnapshot snapshot;
        final Runnable previousRetired;
        Candidate candidate;
        volatile boolean completed;
        volatile Throwable failure;
        private boolean previousSettled;
        Publication(RetainedSceneSnapshot snapshot, Runnable previousRetired) {
            this.snapshot = snapshot;
            this.previousRetired = previousRetired;
        }
        void complete(Throwable failure) {
            this.failure = failure;
            completed = true;
        }
        synchronized void retirePrevious(Runnable release) {
            if (previousSettled) return;
            previousSettled = true;
            Throwable failure = null;
            if (release != null) {
                try {
                    release.run();
                } catch (Throwable releaseFailure) {
                    failure = releaseFailure;
                }
            }
            try {
                previousRetired.run();
            } catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("retained scene retirement failed", failure);
        }
    }
    private record NativeInstance(RetainedSceneSnapshot.Instance logical, NativeMesh mesh,
                                  dev.comfyfluffy.caustica.api.geometry.GeometryTransform previousTransform,
                                  int geometryBase, int sbtRecordOffset) { }
    private record CompletedBuild(Publication publication, Throwable failure) { }

    private static class Candidate {
        final long revision;
        final Map<Long, NativeMesh> meshes;
        final Map<SceneId, List<NativeInstance>> instances;
        final Map<SceneId, SceneContent> content;
        List<NativeMesh> unsubmitted = List.of();
        List<RtAccel.PreparedBlas> builds = List.of();
        boolean accepted;
        volatile boolean buildResourcesReleased;
        Candidate(long revision, Map<Long, NativeMesh> meshes, Map<SceneId, List<NativeInstance>> instances,
                  Map<SceneId, SceneContent> content) {
            this.revision = revision;
            this.meshes = Map.copyOf(meshes);
            this.instances = Map.copyOf(instances);
            this.content = Map.copyOf(content);
        }
        NativeSnapshot publish() { return new NativeSnapshot(revision, meshes, instances, content); }
        void release() { meshes.values().forEach(NativeMesh::release); }
        void releaseRejected() {
            if (accepted) return;
            for (NativeMesh mesh : meshes.values()) {
                if (unsubmitted.contains(mesh)) RtAccel.releaseTransientBlas(mesh.buildOperation);
                else mesh.release();
            }
        }
        void releaseAfterDeviceIdle() {
            if (!buildResourcesReleased && !builds.isEmpty()) {
                RtAccel.freeBlasScratch(builds);
                buildResourcesReleased = true;
            }
            release();
        }
    }

    private static final class NativeSnapshot extends Candidate {
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();
        NativeSnapshot(long revision, Map<Long, NativeMesh> meshes,
                       Map<SceneId, List<NativeInstance>> instances, Map<SceneId, SceneContent> content) {
            super(revision, meshes, instances, content);
        }
    }

    private static class NativeMesh {
        final RetainedSceneSnapshot.Mesh logical;
        final RtAccel.PreparedBlas buildOperation;
        final RtAccel accel;
        final GpuBuffer backing;
        int references = 1;
        NativeMesh(RetainedSceneSnapshot.Mesh logical, RtAccel.PreparedBlas buildOperation, RtAccel accel,
                   GpuBuffer backing) {
            this.logical = logical;
            this.buildOperation = buildOperation;
            this.accel = accel;
            this.backing = backing;
        }
        synchronized void retain() { references++; }
        NativeMesh withLogical(RetainedSceneSnapshot.Mesh next) {
            return new NativeMeshView(this, next);
        }
        synchronized void release() {
            if (--references == 0) RtAccel.destroyCallerOwnedAccel(accel, backing);
        }
    }

    private static final class NativeMeshView extends NativeMesh {
        private final NativeMesh owner;
        NativeMeshView(NativeMesh owner, RetainedSceneSnapshot.Mesh logical) {
            super(logical, owner.buildOperation, owner.accel, owner.backing);
            this.owner = owner;
        }
        @Override synchronized void retain() { owner.retain(); }
        @Override synchronized void release() { owner.release(); }
    }
}
