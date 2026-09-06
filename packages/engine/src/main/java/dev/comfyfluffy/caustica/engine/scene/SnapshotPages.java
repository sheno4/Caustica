package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.support.SharedResource;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;

/** Mutable index whose captures share immutable pages and their resource claims. */
final class SnapshotPages<T> {
    private static final int PAGE_SIZE = 128;
    private final TreeMap<Long, Bucket<T>> buckets = new TreeMap<>();
    private SharedResource<List<T>> captured;

    void put(long identity, T value, SharedResource<?> owner) {
        invalidate();
        var bucket = buckets.computeIfAbsent(identity / PAGE_SIZE, ignored -> new Bucket<>());
        bucket.invalidate();
        bucket.entries.put(identity, new Entry<>(value, owner));
    }

    void remove(long identity) {
        invalidate();
        var bucket = buckets.get(identity / PAGE_SIZE);
        bucket.invalidate();
        bucket.entries.remove(identity);
        if (bucket.entries.isEmpty()) buckets.remove(identity / PAGE_SIZE);
    }

    private void invalidate() {
        if (captured != null) captured.close();
        captured = null;
    }

    SharedResource<List<T>> capture() {
        if (captured == null) {
            var claims = new ArrayList<SharedResource<List<T>>>(buckets.size());
            var pages = new ArrayList<List<T>>(buckets.size());
            for (var bucket : buckets.values()) {
                var page = bucket.capture();
                claims.add(page);
                pages.add(page.get());
            }
            captured = SharedResource.owned(new Values<>(pages), ignored -> claims.forEach(SharedResource::close));
        }
        return captured.retain();
    }

    private record Entry<T>(T value, SharedResource<?> owner) { }

    private static final class Bucket<T> {
        final TreeMap<Long, Entry<T>> entries = new TreeMap<>();
        SharedResource<List<T>> captured;

        void invalidate() {
            if (captured != null) captured.close();
            captured = null;
        }

        SharedResource<List<T>> capture() {
            if (captured == null) {
                var owners = new ArrayList<SharedResource<?>>(entries.size());
                var values = new ArrayList<T>(entries.size());
                for (var entry : entries.values()) {
                    values.add(entry.value);
                    owners.add(entry.owner.retain());
                }
                captured = SharedResource.owned(List.copyOf(values), ignored -> owners.forEach(SharedResource::close));
            }
            return captured.retain();
        }
    }

    /** The page arrays are immutable; retaining the capture keeps their borrowed values valid. */
    static final class Values<T> extends AbstractList<T> implements SnapshotList<T> {
        private final List<List<T>> pages;
        private final int[] ends;
        private final int size;

        Values(List<List<T>> pages) {
            this.pages = List.copyOf(pages);
            ends = new int[pages.size()];
            int count = 0;
            for (int index = 0; index < pages.size(); index++) {
                count += pages.get(index).size();
                ends[index] = count;
            }
            size = count;
        }

        @Override public int size() { return size; }

        @Override public List<List<T>> pages() { return pages; }

        @Override public T get(int index) {
            Objects.checkIndex(index, size);
            int page = Arrays.binarySearch(ends, index + 1);
            if (page < 0) page = -page - 1;
            return pages.get(page).get(index - (page == 0 ? 0 : ends[page - 1]));
        }

        @Override public Iterator<T> iterator() {
            return new Iterator<>() {
                int page;
                int offset;
                @Override public boolean hasNext() { return page < pages.size(); }
                @Override public T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    var values = pages.get(page);
                    T result = values.get(offset++);
                    if (offset == values.size()) { page++; offset = 0; }
                    return result;
                }
            };
        }
    }
}
