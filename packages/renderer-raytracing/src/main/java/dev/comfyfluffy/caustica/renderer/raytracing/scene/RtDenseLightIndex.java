package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.ToLongFunction;

/** Identity entries retain page slots and local offsets, so shared pages only update their dense bases. */
final class RtDenseLightIndex<T> implements Long2IntFunction {
    private final ToLongFunction<? super T> identity;
    private final Long2LongOpenHashMap locations;
    private IdentityHashMap<List<T>, Integer> pages = new IdentityHashMap<>();
    private final IntArrayList freeSlots = new IntArrayList();
    private int[] bases = new int[0];
    private int nextSlot;
    private List<T> currentLights;

    RtDenseLightIndex(int expectedSize, ToLongFunction<? super T> identity) {
        this.identity = identity;
        locations = new Long2LongOpenHashMap(expectedSize);
        locations.defaultReturnValue(-1L);
    }

    /** Updates run before joined readers begin; no reader retains this lookup across the next update. */
    void update(List<T> lights) {
        currentLights = null;
        var inputs = SnapshotList.pagesOf(lights);
        var next = new IdentityHashMap<List<T>, Integer>(inputs.size());
        for (List<T> page : inputs) next.put(page, pages.get(page));
        // Remove every retired page before insertion: identities may migrate between replacement pages.
        for (var entry : pages.entrySet()) {
            if (next.containsKey(entry.getKey())) continue;
            for (T light : entry.getKey()) locations.remove(identity.applyAsLong(light));
            freeSlots.add(entry.getValue().intValue());
        }
        int base = 0;
        for (List<T> page : inputs) {
            Integer slot = next.get(page);
            if (slot == null) {
                slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.removeInt(freeSlots.size() - 1);
                if (slot >= bases.length) bases = Arrays.copyOf(bases, Math.max(slot + 1, Math.max(8, bases.length * 2)));
                int local = 0;
                for (T light : page) {
                    locations.put(identity.applyAsLong(light), ((long) slot << 32) | Integer.toUnsignedLong(local++));
                }
                next.put(page, slot);
            }
            bases[slot] = base;
            base += page.size();
        }
        pages = next;
        currentLights = lights;
    }

    boolean matches(List<T> lights) { return currentLights == lights; }

    @Override public int get(long identity) {
        long location = locations.get(identity);
        return location == -1L ? -1 : bases[(int) (location >>> 32)] + (int) location;
    }

    @Override public boolean containsKey(long identity) { return locations.containsKey(identity); }

    @Override public int getOrDefault(long identity, int fallback) {
        int dense = get(identity);
        return dense < 0 ? fallback : dense;
    }

    @Override public int defaultReturnValue() { return -1; }
}
