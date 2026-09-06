package dev.comfyfluffy.caustica.minecraft.client.terrain;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Render-thread state. Request identity rejects results from superseded extraction groups. */
final class TerrainUpdates<T> {
    final Long2ObjectOpenHashMap<Section<T>> sections = new Long2ObjectOpenHashMap<>();
    private final LinkedHashSet<Group<T>> groups = new LinkedHashSet<>();
    private final LinkedHashSet<Group<T>> ready = new LinkedHashSet<>();
    private final LinkedHashSet<Request<T>> pending = new LinkedHashSet<>();

    private final java.util.function.Consumer<T> discard;
    TerrainUpdates() { this(value -> { }); }
    TerrainUpdates(java.util.function.Consumer<T> discard) { this.discard = discard; }

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
                        pending.remove(request);
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
            group.requests.add(request);
            if (section.wanted) {
                pending.add(request);
                group.remaining++;
            }
        }
        groups.add(group);
        if (group.remaining == 0) ready.add(group);
    }

    boolean complete(Request<T> request, T result) {
        if (request.section.request != request) return false;
        pending.remove(request);
        request.result = result;
        request.complete = true;
        if (--request.group.remaining == 0) ready.add(request.group);
        return true;
    }

    /** Only current requests awaiting extraction participate in dispatch selection. */
    Collection<Request<T>> pending() {
        return pending;
    }

    void dispatched(Request<T> request) {
        pending.remove(request);
        request.dispatched = true;
    }

    void retry(Request<T> request) {
        request.dispatched = false;
        pending.add(request);
    }

    /** Every complete group publishes at the next frame boundary; unfinished neighbors remain atomic. */
    List<Group<T>> ready() {
        return List.copyOf(ready);
    }

    void published(List<Group<T>> published) {
        for (Group<T> group : published) {
            groups.remove(group);
            ready.remove(group);
            for (Request<T> request : group.requests) {
                Section<T> section = request.section;
                section.request = null;
                section.ready = section.wanted;
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
        for (Section<T> section : sections.values()) section.request = null;
        sections.clear();
        groups.clear();
        ready.clear();
        pending.clear();
    }

    static final class Section<T> {
        final long key;
        boolean wanted;
        boolean ready;
        Request<T> request;
        Section(long key) { this.key = key; }
    }

    static final class Group<T> {
        final List<Request<T>> requests = new ArrayList<>();
        int remaining;
    }

    static final class Request<T> {
        final Section<T> section;
        final Group<T> group;
        boolean dispatched;
        boolean complete;
        T result;
        Request(Section<T> section, Group<T> group) {
            this.section = section;
            this.group = group;
            complete = !section.wanted;
        }
    }
}
