package dev.comfyfluffy.caustica.minecraft.client.terrain;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Workers own section/group bookkeeping; render reads readiness and reserves atomic extraction tokens. */
final class TerrainUpdates<T> {
    final Long2ObjectOpenHashMap<Section<T>> sections = new Long2ObjectOpenHashMap<>();
    private final LinkedHashSet<Group<T>> groups = new LinkedHashSet<>();
    private final LinkedHashSet<Group<T>> ready = new LinkedHashSet<>();
    private final ConcurrentHashMap<Long, Request<T>> current = new ConcurrentHashMap<>();
    private final Set<Long> published = ConcurrentHashMap.newKeySet();
    private final Consumer<T> discard;
    private final BiConsumer<Long, Request<T>> pending;
    TerrainUpdates() { this(value -> { }); }
    TerrainUpdates(Consumer<T> discard) { this(discard, (key, request) -> { }); }
    TerrainUpdates(Consumer<T> discard, BiConsumer<Long, Request<T>> pending) {
        this.discard = discard;
        this.pending = pending;
    }

    boolean isReady(long key) { return published.contains(key); }

    /** Invalidations reject extraction immediately; the coordination worker rebuilds neighboring groups. */
    void invalidate(Collection<Long> keys) {
        for (long key : keys) {
            var request = current.get(key);
            while (request != null) {
                request.invalidate();
                var latest = current.get(key);
                if (latest == request) break;
                request = latest;
            }
        }
    }

    void want(long key) {
        Section<T> section = sections.get(key);
        if (section != null && section.wanted) return;
        if (section == null) {
            section = new Section<>(key);
            sections.put(key, section);
        }
        section.wanted = true;
        rebuild(List.of(key));
    }

    void remove(long key) {
        Section<T> section = sections.get(key);
        if (section == null || !section.wanted) return;
        section.wanted = false;
        rebuild(List.of(key));
    }

    /** Only visible sections need a shared replacement boundary during world extraction. */
    void dirty(Collection<Long> changed) {
        var visible = new ArrayList<Long>();
        for (long key : changed) {
            Section<T> section = sections.get(key);
            if (section == null) continue;
            if (section.ready) visible.add(key);
            else rebuild(List.of(key));
        }
        rebuild(visible);
    }

    /** Rebuild the union of overlapping groups; no surviving member becomes an independent edit. */
    void rebuild(Collection<Long> changed) {
        var members = new LongOpenHashSet();
        for (long key : changed) {
            Section<T> section = sections.get(key);
            if (section == null) continue;
            members.add(key);
            if (section.request != null) {
                Group<T> old = section.request.group;
                if (groups.remove(old)) {
                    ready.remove(old);
                    for (Request<T> request : old.requests) {
                        request.invalidate();
                        pending.accept(request.section.key, null);
                        if (request.result != null) discard.accept(request.result);
                    }
                }
                for (Request<T> request : old.requests) members.add(request.section.key);
            }
        }
        if (members.isEmpty()) return;
        Group<T> group = new Group<>();
        for (long key : members) {
            Section<T> section = sections.get(key);
            Request<T> request = new Request<>(section, group);
            section.request = request;
            current.put(key, request);
            group.requests.add(request);
            if (section.wanted) {
                pending.accept(key, request);
                group.remaining++;
            }
        }
        groups.add(group);
        if (group.remaining == 0) ready.add(group);
    }

    boolean complete(Request<T> request, T result) {
        if (request.section.request != request || !request.acceptResult()) return false;
        pending.accept(request.section.key, null);
        request.result = result;
        if (--request.group.remaining == 0) ready.add(request.group);
        return true;
    }

    void dispatched(Request<T> request) {
        if (request.section.request != request || !request.markDispatched()) return;
        pending.accept(request.section.key, null);
    }

    void retry(Request<T> request) {
        if (request.section.request != request || !request.retry()) return;
        pending.accept(request.section.key, request);
    }

    /** Complete groups are available for worker publication; unfinished neighbors remain atomic. */
    List<Group<T>> ready() {
        var result = new ArrayList<Group<T>>(ready.size());
        for (var group : ready) if (group.availableForPublication()) result.add(group);
        return result;
    }

    /** Scheduling probes readiness without collecting groups or constructing per-group stream state. */
    boolean hasReadyGroup() {
        for (var group : ready) if (group.availableForPublication()) return true;
        return false;
    }

    /** Each successful claim accepts one atomic group; later dirtiness requests its next revision. */
    boolean claimPublication(List<Group<T>> candidates) {
        int claimed = 0;
        for (var group : candidates) {
            if (!group.publication.compareAndSet(0, Group.CLAIMED)) {
                for (int i = 0; i < claimed; i++) candidates.get(i).releasePublication();
                return false;
            }
            claimed++;
        }
        return true;
    }

    void releasePublication(List<Group<T>> candidates) {
        candidates.forEach(Group::releasePublication);
    }

    void published(List<Group<T>> published) {
        for (Group<T> group : published) {
            groups.remove(group);
            ready.remove(group);
            for (Request<T> request : group.requests) {
                Section<T> section = request.section;
                request.invalidate();
                current.remove(section.key, request);
                section.request = null;
                section.ready = section.wanted;
                if (section.ready) this.published.add(section.key);
                else this.published.remove(section.key);
                if (!section.wanted) sections.remove(section.key);
            }
        }
    }

    void clear() {
        for (Group<T> group : groups) {
            for (Request<T> request : group.requests) {
                if (request.result != null) discard.accept(request.result);
            }
        }
        for (Section<T> section : sections.values()) {
            if (section.request != null) section.request.invalidate();
            section.request = null;
            pending.accept(section.key, null);
        }
        sections.clear();
        groups.clear();
        ready.clear();
        current.clear();
        published.clear();
    }

    static final class Section<T> {
        final long key;
        boolean wanted;
        boolean ready;
        Request<T> request;
        Section(long key) { this.key = key; }
    }

    static final class Group<T> {
        private static final int INVALID = 1, CLAIMED = 2;
        private final AtomicInteger publication = new AtomicInteger();
        final List<Request<T>> requests = new ArrayList<>();
        int remaining;
        boolean valid() { return (publication.get() & INVALID) == 0; }
        boolean availableForPublication() { return publication.get() == 0; }
        // Invalidation rejects future claims without revoking an already accepted publication.
        void invalidate() { publication.getAndUpdate(state -> state | INVALID); }
        void releasePublication() { publication.getAndUpdate(state -> state & ~CLAIMED); }
    }

    static final class Request<T> {
        private static final int PENDING = 0, CAPTURING = 1, DISPATCHED = 2, COMPLETE = 3, INVALID = 4;
        private final AtomicInteger extraction;
        final Section<T> section;
        final Group<T> group;
        T result;
        Request(Section<T> section, Group<T> group) {
            this.section = section;
            this.group = group;
            extraction = new AtomicInteger(section.wanted ? PENDING : COMPLETE);
        }
        boolean complete() { return extraction.get() == COMPLETE; }
        boolean dispatched() { return extraction.get() == DISPATCHED; }
        /** Render-side readiness uses only the token's atomic state, without reading coordinator maps. */
        boolean awaitingExtraction() { return group.valid() && extraction.get() == PENDING; }
        boolean reserve() { return group.valid() && extraction.compareAndSet(PENDING, CAPTURING); }
        boolean extracted() { return group.valid() && extraction.compareAndSet(CAPTURING, DISPATCHED); }
        boolean valid() { return group.valid() && extraction.get() != INVALID; }
        void invalidate() { group.invalidate(); extraction.set(INVALID); }
        private boolean markDispatched() {
            if (!group.valid()) return false;
            return extraction.compareAndSet(PENDING, DISPATCHED) || extraction.get() == DISPATCHED;
        }
        private boolean retry() {
            if (!group.valid()) return false;
            return extraction.compareAndSet(DISPATCHED, PENDING) || extraction.compareAndSet(CAPTURING, PENDING);
        }
        private boolean acceptResult() {
            if (!group.valid()) return false;
            int state = extraction.get();
            while (state != INVALID && state != COMPLETE) {
                if (extraction.compareAndSet(state, COMPLETE)) return true;
                state = extraction.get();
            }
            return false;
        }
    }
}
