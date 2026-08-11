package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;

/** Owns retained geometry residency, stable table slots, rebasing, publication, and exact GPU retirement. */
public final class RtRetainedGeometryScene<M> {
    private static final int ENTRY_BYTES = RtGeometryAbi.RECORD_BYTES;

    private final IntSupplier initialCapacity;
    private final int geometrySemanticFlags;
    private final Long2ObjectOpenHashMap<Resident<M>> residents = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet published = new LongOpenHashSet();
    private final SlotRegistry<Resident<M>> slots = new SlotRegistry<>();
    private final ArrayList<RtAccel.Instance> instanceList = new ArrayList<>();
    private final ConcurrentLinkedQueue<Generation> recycledGenerations = new ConcurrentLinkedQueue<>();

    private GpuBuffer buffer;
    private int capacity;
    private List<RtAccel.Instance> instances;
    private long dirtyStart = Long.MAX_VALUE;
    private long dirtyEnd;
    private int originX;
    private int originY;
    private int originZ;
    private boolean ready;

    public RtRetainedGeometryScene(IntSupplier initialCapacity) {
        this(initialCapacity, 0);
    }

    public RtRetainedGeometryScene(IntSupplier initialCapacity, int geometrySemanticFlags) {
        this.initialCapacity = initialCapacity;
        this.geometrySemanticFlags = geometrySemanticFlags;
    }

    public boolean ready() {
        return ready;
    }

    public boolean contains(long key) {
        return residents.containsKey(key);
    }

    public boolean isPublished(long key) {
        return published.contains(key);
    }

    public Resident<M> get(long key) {
        return residents.get(key);
    }

    public Resident<M> stageRemoval(long key) {
        return residents.remove(key);
    }

    public void stageUndesired(LongPredicate desired, List<Resident<M>> removals) {
        var iterator = residents.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Resident<M>> entry = iterator.next();
            if (!desired.test(entry.getLongKey())) {
                removals.add(entry.getValue());
                iterator.remove();
            }
        }
    }

    public boolean isEmpty() {
        return residents.isEmpty();
    }

    public Collection<Resident<M>> residentValues() {
        return residents.values();
    }

    public List<RtAccel.Instance> instances() {
        return instances;
    }

    public RtGeometryAbi.TablePrefix tablePrefix() {
        return new RtGeometryAbi.TablePrefix(buffer.mapped, slots.highWaterMark());
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public boolean shouldRebase(int x, int y, int z, int distance) {
        return !ready || buffer == null || instances == null
                || Math.abs(x - originX) > distance
                || Math.abs(y - originY) > distance
                || Math.abs(z - originZ) > distance;
    }

    public Publication<M> publishBatch(GpuContext ctx, List<RtRetainedGeometryBuilds.Prepared<M>> prepared,
                                       List<Resident<M>> removals, LongPredicate desired,
                                       boolean rebase, int newOriginX, int newOriginY, int newOriginZ) {
        GraphicsUse lastGraphicsUse = ctx.gpuExecutor().latestGraphicsUse();
        int baseX = rebase ? newOriginX : originX;
        int baseY = rebase ? newOriginY : originY;
        int baseZ = rebase ? newOriginZ : originZ;
        ArrayList<Removal<M>> removed = new ArrayList<>(removals.size());
        ArrayList<Update<M>> updated = new ArrayList<>(prepared.size());

        for (Resident<M> geometry : removals) {
            Resident<M> current = residents.get(geometry.key);
            boolean removesCurrent = current == geometry;
            int previousSlot = geometry.slotIndex();
            removePublished(geometry);
            removed.add(new Removal<>(geometry, previousSlot, removesCurrent));
        }
        retireResidents(ctx, lastGraphicsUse, removals);

        if (!prepared.isEmpty()) {
            Generation oldGeneration = beginWriteGeneration(ctx, liveSlotCapacity(prepared));
            if (oldGeneration != null) {
                retireGeneration(ctx, lastGraphicsUse, oldGeneration);
            }
        }

        for (RtRetainedGeometryBuilds.Prepared<M> built : prepared) {
            Resident<M> geometry = new Resident<>(built.key(), built.textureCoordinates(), built.primitives(),
                    built.blas().accel, built.triangleBases(), built.originX(), built.originY(), built.originZ(),
                    built.metadata());
            if (!desired.test(built.key())) {
                ctx.gpuExecutor().retireUnpublished(geometry::destroy);
                continue;
            }
            Resident<M> previous = residents.get(built.key());
            if (previous != null && slots.isCurrent(previous.slot, previous)) {
                geometry.slot = slots.replace(previous.slot, geometry);
                geometry.instanceIndex = previous.instanceIndex;
                residents.put(built.key(), geometry);
                write(geometry);
                instanceList.set(geometry.instanceIndex, instanceFor(geometry, baseX, baseY, baseZ));
                retireResidents(ctx, lastGraphicsUse, List.of(previous));
            } else {
                geometry.slot = slots.allocate(geometry);
                geometry.instanceIndex = instanceList.size();
                residents.put(built.key(), geometry);
                write(geometry);
                instanceList.add(instanceFor(geometry, baseX, baseY, baseZ));
            }
            published.add(built.key());
            updated.add(new Update<>(geometry, previous));
        }
        flushWrites();

        boolean becameEmpty = residents.isEmpty();
        if (becameEmpty) {
            Generation emptyGeneration = detachGeneration();
            if (emptyGeneration != null) {
                retireGeneration(ctx, lastGraphicsUse, emptyGeneration);
            }
            resetPublishedState();
            ensureEmpty(ctx);
        } else {
            if (rebase) {
                rebaseInstances(baseX, baseY, baseZ);
                originX = newOriginX;
                originY = newOriginY;
                originZ = newOriginZ;
            }
            instances = instanceList;
            ready = true;
        }
        return new Publication<>(List.copyOf(removed), List.copyOf(updated), becameEmpty);
    }

    public void ensureEmpty(GpuContext ctx) {
        if (buffer == null) {
            Generation generation = acquireGeneration(ctx, initialCapacity.getAsInt());
            buffer = generation.buffer;
            capacity = generation.capacity;
        }
        if (instances == null) {
            instances = instanceList;
        }
        ready = true;
    }

    public void destroyRecycledGenerations() {
        Generation generation;
        while ((generation = recycledGenerations.poll()) != null) {
            generation.buffer.destroy();
        }
    }

    public void clearAsync(GpuContext ctx, Collection<Resident<M>> detached) {
        GraphicsUse lastGraphicsUse = ctx.gpuExecutor().latestGraphicsUse();
        Generation oldGeneration = detachGeneration();
        Set<Resident<M>> oldGeometry = identitySet(detached);
        oldGeometry.addAll(residents.values());
        resetAllState();
        if (oldGeneration != null) {
            retireGeneration(ctx, lastGraphicsUse, oldGeneration);
        }
        if (!oldGeometry.isEmpty()) {
            ArrayList<Resident<M>> retirement = new ArrayList<>(oldGeometry);
            ctx.gpuExecutor().retireAfterGraphics(lastGraphicsUse, () -> destroyResidents(retirement));
        }
        ensureEmpty(ctx);
    }

    public void destroyAfterDeviceIdle(Collection<Resident<M>> detached) {
        Set<Resident<M>> oldGeometry = identitySet(detached);
        oldGeometry.addAll(residents.values());
        Generation generation = detachGeneration();
        if (generation != null) {
            generation.buffer.destroy();
        }
        destroyRecycledGenerations();
        destroyResidents(oldGeometry);
        resetAllState();
    }

    private int liveSlotCapacity(List<RtRetainedGeometryBuilds.Prepared<M>> prepared) {
        int needed = slots.highWaterMark();
        int free = slots.freeCount();
        for (RtRetainedGeometryBuilds.Prepared<M> built : prepared) {
            Resident<M> previous = residents.get(built.key());
            if (previous != null && slots.isCurrent(previous.slot, previous)) {
                needed = Math.max(needed, previous.slot.index + 1);
            } else if (free > 0) {
                free--;
            } else {
                needed++;
            }
        }
        return needed;
    }

    private Generation beginWriteGeneration(GpuContext ctx, int minCapacity) {
        int newCapacity = Math.max(initialCapacity.getAsInt(), capacity);
        while (newCapacity < minCapacity) {
            newCapacity <<= 1;
        }
        Generation writable = acquireGeneration(ctx, newCapacity);
        Generation oldGeneration = buffer == null ? null : new Generation(buffer, capacity);
        int oldCapacity = capacity;
        buffer = writable.buffer;
        capacity = writable.capacity;
        if (oldGeneration != null && oldCapacity > 0) {
            MemoryUtil.memCopy(oldGeneration.buffer.mapped, buffer.mapped, (long) oldCapacity * ENTRY_BYTES);
            markDirty(0L, (long) oldCapacity * ENTRY_BYTES);
        }
        return oldGeneration;
    }

    private Generation acquireGeneration(GpuContext ctx, int minCapacity) {
        Generation generation;
        while ((generation = recycledGenerations.poll()) != null) {
            if (generation.capacity >= minCapacity) {
                return generation;
            }
            ctx.gpuExecutor().retireUnpublished(generation.buffer::destroy);
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        GpuBuffer newBuffer = ctx.createBuffer((long) minCapacity * ENTRY_BYTES, storage, true,
                "retained geometry table " + minCapacity + " slots");
        return new Generation(newBuffer, minCapacity);
    }

    private Generation detachGeneration() {
        if (buffer == null) {
            return null;
        }
        Generation generation = new Generation(buffer, capacity);
        buffer = null;
        capacity = 0;
        return generation;
    }

    private void retireGeneration(GpuContext ctx, GraphicsUse lastGraphicsUse, Generation generation) {
        ctx.gpuExecutor().retireAfterGraphics(lastGraphicsUse, () -> recycledGenerations.add(generation));
    }

    private void removePublished(Resident<M> geometry) {
        Resident<M> current = residents.get(geometry.key);
        if (current == geometry) {
            residents.remove(geometry.key);
        }
        boolean currentSlot = slots.isCurrent(geometry.slot, geometry);
        if (current == geometry || currentSlot) {
            published.remove(geometry.key);
        }
        if (geometry.instanceIndex >= 0 && currentSlot) {
            int removeIndex = geometry.instanceIndex;
            int lastIndex = instanceList.size() - 1;
            if (removeIndex != lastIndex) {
                RtAccel.Instance moved = instanceList.get(lastIndex);
                instanceList.set(removeIndex, moved);
                Resident<M> movedGeometry = slots.get(moved.customIndex());
                movedGeometry.instanceIndex = removeIndex;
            }
            instanceList.remove(lastIndex);
            geometry.instanceIndex = -1;
        }
        slots.remove(geometry.slot, geometry);
        geometry.slot = Slot.invalid();
    }

    private void write(Resident<M> geometry) {
        long offset = (long) geometry.slot.index * ENTRY_BYTES;
        long address = buffer.mapped + offset;
        RtGeometryAbi.writeRecord(address, geometry.primitives.deviceAddress, 0L,
                geometry.textureCoordinates.deviceAddress, 0L, 0f, 0f, 0f,
                geometry.triangleBases[0], geometry.triangleBases[1], geometry.triangleBases[2],
                RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES | geometrySemanticFlags);
        markDirty(offset, ENTRY_BYTES);
    }

    private void flushWrites() {
        if (dirtyStart == Long.MAX_VALUE) {
            return;
        }
        buffer.flush(dirtyStart, dirtyEnd - dirtyStart);
        dirtyStart = Long.MAX_VALUE;
        dirtyEnd = 0L;
    }

    private void markDirty(long offset, long length) {
        dirtyStart = Math.min(dirtyStart, offset);
        dirtyEnd = Math.max(dirtyEnd, offset + length);
    }

    private RtAccel.Instance instanceFor(Resident<M> geometry, int baseX, int baseY, int baseZ) {
        return new RtAccel.Instance(instanceTransform(geometry.originX, geometry.originY, geometry.originZ,
                baseX, baseY, baseZ), geometry.blas.deviceAddress, geometry.slot.index);
    }

    static float[] instanceTransform(int x, int y, int z, int baseX, int baseY, int baseZ) {
        return new float[]{1, 0, 0, x - baseX, 0, 1, 0, y - baseY, 0, 0, 1, z - baseZ};
    }

    private void rebaseInstances(int baseX, int baseY, int baseZ) {
        for (int i = 0; i < instanceList.size(); i++) {
            RtAccel.Instance instance = instanceList.get(i);
            Resident<M> geometry = slots.get(instance.customIndex());
            instanceList.set(i, instanceFor(geometry, baseX, baseY, baseZ));
        }
    }

    private void resetPublishedState() {
        slots.reset();
        instanceList.clear();
        instances = null;
        published.clear();
    }

    private void resetAllState() {
        residents.clear();
        resetPublishedState();
        buffer = null;
        capacity = 0;
        ready = false;
    }

    private static <M> Set<Resident<M>> identitySet(Collection<Resident<M>> values) {
        Set<Resident<M>> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(values);
        return set;
    }

    private static void retireResidents(GpuContext ctx, GraphicsUse lastGraphicsUse,
                                        Collection<? extends Resident<?>> geometry) {
        for (Resident<?> resident : geometry) {
            ctx.gpuExecutor().retireAfterGraphics(lastGraphicsUse, resident::destroy);
        }
    }

    private static void destroyResidents(Collection<? extends Resident<?>> geometry) {
        Throwable failure = null;
        for (Resident<?> resident : geometry) {
            try {
                resident.destroy();
            } catch (Throwable destroyFailure) {
                if (failure == null) failure = destroyFailure;
                else failure.addSuppressed(destroyFailure);
            }
        }
        if (failure != null) {
            throw new RuntimeException("Failed to destroy retained geometry", failure);
        }
    }

    private record Generation(GpuBuffer buffer, int capacity) {
    }

    public record Publication<M>(List<Removal<M>> removals, List<Update<M>> updates, boolean empty) {
    }

    public record Removal<M>(Resident<M> geometry, int slot, boolean removedCurrent) {
    }

    public record Update<M>(Resident<M> geometry, Resident<M> previous) {
    }

    public static final class Resident<M> {
        private final long key;
        private final GpuBuffer textureCoordinates;
        private final GpuBuffer primitives;
        private final RtAccel blas;
        private final int[] triangleBases;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final M metadata;
        private Slot slot = Slot.invalid();
        private int instanceIndex = -1;

        private Resident(long key, GpuBuffer textureCoordinates, GpuBuffer primitives, RtAccel blas,
                         int[] triangleBases, int originX, int originY, int originZ, M metadata) {
            this.key = key;
            this.textureCoordinates = textureCoordinates;
            this.primitives = primitives;
            this.blas = blas;
            this.triangleBases = triangleBases;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.metadata = metadata;
        }

        public long key() {
            return key;
        }

        public int slotIndex() {
            return slot.index;
        }

        public int originX() {
            return originX;
        }

        public int originY() {
            return originY;
        }

        public int originZ() {
            return originZ;
        }

        public M metadata() {
            return metadata;
        }

        private void destroy() {
            blas.destroy();
            primitives.destroy();
            textureCoordinates.destroy();
        }
    }

    static final class SlotRegistry<T> {
        private final ArrayList<T> values = new ArrayList<>();
        private final ArrayList<Integer> free = new ArrayList<>();
        private int next;
        private int epoch;

        Slot allocate(T value) {
            int index = free.isEmpty() ? next++ : free.remove(free.size() - 1);
            while (values.size() <= index) values.add(null);
            values.set(index, value);
            return new Slot(epoch, index);
        }

        Slot replace(Slot slot, T value) {
            if (!isCurrent(slot, values.get(slot.index))) {
                throw new IllegalStateException("cannot replace an inactive geometry slot");
            }
            values.set(slot.index, value);
            return slot;
        }

        boolean remove(Slot slot, T value) {
            if (!isCurrent(slot, value)) return false;
            values.set(slot.index, null);
            free.add(slot.index);
            return true;
        }

        boolean isCurrent(Slot slot, T value) {
            return slot.epoch == epoch && slot.index >= 0 && slot.index < values.size()
                    && values.get(slot.index) == value;
        }

        T get(int index) {
            return values.get(index);
        }

        int highWaterMark() {
            return next;
        }

        int freeCount() {
            return free.size();
        }

        void reset() {
            epoch++;
            next = 0;
            free.clear();
            values.clear();
        }
    }

    record Slot(int epoch, int index) {
        static Slot invalid() {
            return new Slot(-1, -1);
        }
    }
}
