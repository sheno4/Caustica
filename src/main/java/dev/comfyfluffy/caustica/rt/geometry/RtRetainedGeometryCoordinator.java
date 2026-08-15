package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;

import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;

/**
 * Engine-owned retained packed geometry: upload/build submission, stable records, instances, and
 * retirement form one lifetime. Sources supply packed data and decide whether it is still current;
 * they never own the GPU objects created for that data.
 */
public final class RtRetainedGeometryCoordinator<M> {
    private final RtRetainedGeometryScene<M> scene;

    public RtRetainedGeometryCoordinator(IntSupplier initialCapacity, int geometrySemanticFlags) {
        scene = new RtRetainedGeometryScene<>(initialCapacity, geometrySemanticFlags);
    }

    /** Allocates and fills the engine-owned resources for one replacement candidate. */
    public Prepared<M> prepare(GpuContext ctx, RtPackedGeometry<M> packed,
                               RtAccel.OpacityMicromapInput opacityInput, boolean compactBlas,
                               long key, int originX, int originY, int originZ) {
        return new Prepared<>(RtRetainedGeometryBuilds.prepare(ctx, packed, opacityInput, compactBlas,
                key, originX, originY, originZ));
    }

    /** Records the asynchronous upload/build lifecycle and reports its terminal result exactly once. */
    public void submit(GpuContext ctx, Prepared<M> prepared, BooleanSupplier cancelled,
                       Consumer<Completion<M>> completion) {
        RtRetainedGeometryBuilds.submit(ctx, prepared.value, cancelled,
                result -> completion.accept(new Completion<>(new Prepared<>(result.prepared()), result.build(),
                        result.failure())));
    }

    /** Marks an accepted terminal build as published before its resources enter the retained table. */
    public void publish(GpuContext ctx, Completion<?> completion) {
        ctx.gpuExecutor().markPublished(completion.build);
    }

    /** Releases a candidate that never became resident. */
    public void destroy(Prepared<?> prepared) {
        RtRetainedGeometryBuilds.destroy(prepared.value);
    }

    /** Retires a candidate that never became resident against the executor's unpublished lifetime. */
    public void retireUnpublished(GpuContext ctx, Prepared<?> prepared) {
        ctx.gpuExecutor().retireUnpublished(() -> destroy(prepared));
    }

    public boolean ready() {
        return scene.ready();
    }

    public boolean contains(long key) {
        return scene.contains(key);
    }

    public boolean isPublished(long key) {
        return scene.isPublished(key);
    }

    public Resident<M> get(long key) {
        RtRetainedGeometryScene.Resident<M> resident = scene.get(key);
        return resident == null ? null : new Resident<>(resident);
    }

    public Resident<M> stageRemoval(long key) {
        RtRetainedGeometryScene.Resident<M> resident = scene.stageRemoval(key);
        return resident == null ? null : new Resident<>(resident);
    }

    public void stageUndesired(LongPredicate desired, List<Resident<M>> removals) {
        List<RtRetainedGeometryScene.Resident<M>> staged = new java.util.ArrayList<>();
        scene.stageUndesired(desired, staged);
        staged.stream().map(Resident::new).forEach(removals::add);
    }

    public List<RtAccel.Instance> instances() {
        return scene.instances();
    }

    public RtGeometryAbi.TablePrefix tablePrefix() {
        return scene.tablePrefix();
    }

    public boolean shouldRebase(int x, int y, int z, int distance) {
        return scene.shouldRebase(x, y, z, distance);
    }

    public Publication<M> publishBatch(GpuContext ctx, List<Prepared<M>> prepared,
                                       List<Resident<M>> removals,
                                       LongPredicate desired, boolean rebase,
                                       int newOriginX, int newOriginY, int newOriginZ) {
        List<RtRetainedGeometryBuilds.Prepared<M>> values = prepared.stream().map(Prepared::underlying).toList();
        List<RtRetainedGeometryScene.Resident<M>> removalValues = removals.stream()
                .map(Resident::underlying).toList();
        RtRetainedGeometryScene.Publication<M> publication = scene.publishBatch(ctx, values, removalValues,
                desired, rebase, newOriginX, newOriginY, newOriginZ);
        return new Publication<>(publication.removals().stream()
                .map(removal -> new Removal<>(new Resident<>(removal.geometry()), removal.slot(),
                        removal.removedCurrent()))
                .toList(), publication.updates().stream()
                .map(update -> new Update<>(new Resident<>(update.geometry()),
                        update.previous() == null ? null : new Resident<>(update.previous())))
                .toList(), publication.empty());
    }

    public void ensureEmpty(GpuContext ctx) {
        scene.ensureEmpty(ctx);
    }

    public void clearAsync(GpuContext ctx, Collection<Resident<M>> detached) {
        scene.clearAsync(ctx, detached.stream().map(Resident::underlying).toList());
    }

    public void destroyAfterDeviceIdle(Collection<Resident<M>> detached) {
        scene.destroyAfterDeviceIdle(detached.stream().map(Resident::underlying).toList());
    }

    /** Opaque engine-owned candidate resources. */
    public static final class Prepared<M> {
        private final RtRetainedGeometryBuilds.Prepared<M> value;

        private Prepared(RtRetainedGeometryBuilds.Prepared<M> value) {
            this.value = value;
        }

        private RtRetainedGeometryBuilds.Prepared<M> underlying() {
            return value;
        }
    }

    /** Opaque terminal build state. Sources can accept or reject it, but never manage its executor token. */
    public static final class Completion<M> {
        private final Prepared<M> prepared;
        private final RtGpuExecutor.Build build;
        private final Throwable failure;

        private Completion(Prepared<M> prepared, RtGpuExecutor.Build build, Throwable failure) {
            this.prepared = prepared;
            this.build = build;
            this.failure = failure;
        }

        public Prepared<M> prepared() {
            return prepared;
        }

        public Throwable failure() {
            return failure;
        }
    }

    public record Publication<M>(List<Removal<M>> removals, List<Update<M>> updates, boolean empty) {
    }

    public record Removal<M>(Resident<M> geometry, int slot, boolean removedCurrent) {
    }

    public record Update<M>(Resident<M> geometry, Resident<M> previous) {
    }

    /** Source-visible metadata for a resident record; all GPU ownership remains private to the coordinator. */
    public static final class Resident<M> {
        private final RtRetainedGeometryScene.Resident<M> value;

        private Resident(RtRetainedGeometryScene.Resident<M> value) {
            this.value = value;
        }

        private RtRetainedGeometryScene.Resident<M> underlying() {
            return value;
        }

        public long key() {
            return value.key();
        }

        public int slotIndex() {
            return value.slotIndex();
        }

        public int originX() {
            return value.originX();
        }

        public int originY() {
            return value.originY();
        }

        public int originZ() {
            return value.originZ();
        }

        public M metadata() {
            return value.metadata();
        }
    }
}
