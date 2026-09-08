package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.ToLongFunction;

/** Identity entries reference their page and local offset; shared pages only update their dense base. */
final class RtDenseLightIndex<T> implements Long2IntFunction {
    private final ToLongFunction<? super T> identity;
    private final Long2ObjectOpenHashMap<Location> locations;
    private IdentityHashMap<List<T>, Page> pages = new IdentityHashMap<>();
    private List<T> currentLights;

    RtDenseLightIndex(int expectedSize, ToLongFunction<? super T> identity) {
        this.identity = identity;
        locations = new Long2ObjectOpenHashMap<>(expectedSize);
    }

    /** Updates run before joined readers begin; no reader retains this lookup across the next update. */
    void update(List<T> lights) {
        currentLights = null;
        var inputs = SnapshotList.pagesOf(lights);
        var next = new IdentityHashMap<List<T>, Page>(inputs.size());
        for (List<T> page : inputs) next.put(page, pages.get(page));
        // Remove every retired page before insertion: identities may migrate between replacement pages.
        for (var entry : pages.entrySet()) {
            if (next.containsKey(entry.getKey())) continue;
            for (T light : entry.getKey()) locations.remove(identity.applyAsLong(light));
        }
        int base = 0;
        for (List<T> page : inputs) {
            Page retained = next.get(page);
            if (retained == null) {
                retained = new Page();
                int local = 0;
                for (T light : page) {
                    locations.put(identity.applyAsLong(light), new Location(retained, local++));
                }
                next.put(page, retained);
            }
            retained.base = base;
            base += page.size();
        }
        pages = next;
        currentLights = lights;
    }

    boolean matches(List<T> lights) { return currentLights == lights; }

    @Override public int get(long identity) {
        Location location = locations.get(identity);
        return location == null ? -1 : location.page.base + location.offset;
    }

    @Override public boolean containsKey(long identity) { return locations.containsKey(identity); }

    @Override public int getOrDefault(long identity, int fallback) {
        int dense = get(identity);
        return dense < 0 ? fallback : dense;
    }

    @Override public int defaultReturnValue() { return -1; }

    private static final class Page {
        int base;
    }

    private record Location(Page page, int offset) { }
}
