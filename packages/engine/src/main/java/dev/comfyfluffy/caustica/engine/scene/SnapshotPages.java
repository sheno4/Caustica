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
import java.util.TreeSet;

/** Nonnegative ordinals select buckets in an immutable radix directory with shared subtree ownership. */
final class SnapshotPages<T> {
    private static final int PAGE_SIZE = 128;
    private final TreeMap<Long, Bucket<T>> buckets = new TreeMap<>();
    private final TreeSet<Long> dirty = new TreeSet<>();
    private SharedResource<List<T>> captured = ownValues(null);

    void put(long identity, T value) { put(identity, value, null); }

    void put(long identity, T value, SharedResource<?> owner) {
        long key = identity / PAGE_SIZE;
        var bucket = buckets.computeIfAbsent(key, ignored -> new Bucket<>());
        bucket.entries.put(identity, new Entry<>(value, owner));
        dirty.add(key);
    }

    void remove(long identity) {
        long key = identity / PAGE_SIZE;
        var bucket = buckets.get(key);
        bucket.entries.remove(identity);
        if (bucket.entries.isEmpty()) buckets.remove(key);
        dirty.add(key);
    }

    /** Freezes each edited bucket once; unchanged directory subtrees and pages keep their ownership. */
    void commit() {
        if (dirty.isEmpty()) return;
        var previous = (DirectoryValues<T>) captured.get();
        SharedResource<Node<T>> next = previous.root == null ? null : previous.root.retain();
        for (long key : dirty) {
            var bucket = buckets.get(key);
            var replacement = replace(next, key, bucket == null ? null : bucket.freeze(key));
            if (next != null) next.close();
            next = replacement;
        }
        dirty.clear();
        var replaced = captured;
        captured = ownValues(next);
        replaced.close();
    }

    /** Acquires the committed directory without inspecting mutable buckets or flattening its pages. */
    SharedResource<List<T>> capture() { return captured.retain(); }

    private static <T> SharedResource<List<T>> ownValues(SharedResource<Node<T>> root) {
        var values = new DirectoryValues<>(root);
        return SharedResource.owned(values, ignored -> { if (root != null) root.close(); });
    }

    /** Takes the replacement leaf claim and borrows the old subtree claim. */
    private static <T> SharedResource<Node<T>> replace(SharedResource<Node<T>> current, long key,
                                                       SharedResource<Node<T>> leaf) {
        if (current == null) return leaf;
        Node<T> node = current.get();
        long difference = Long.highestOneBit(key ^ node.key);
        if (difference > node.bit) {
            if (leaf == null) return current.retain();
            return (key & difference) == 0
                    ? branch(difference, leaf, current.retain())
                    : branch(difference, current.retain(), leaf);
        }
        if (node.bit == 0) return leaf;
        if ((key & node.bit) == 0) {
            var left = replace(node.leftOwner, key, leaf);
            return left == null ? node.rightOwner.retain() : branch(node.bit, left, node.rightOwner.retain());
        }
        var right = replace(node.rightOwner, key, leaf);
        return right == null ? node.leftOwner.retain() : branch(node.bit, node.leftOwner.retain(), right);
    }

    /** Branches own exactly two nonempty subtrees; branching bits strictly decrease toward leaves. */
    private static <T> SharedResource<Node<T>> branch(long bit, SharedResource<Node<T>> left,
                                                     SharedResource<Node<T>> right) {
        return SharedResource.owned(new Node<>(bit, left, right), ignored -> {
            left.close();
            right.close();
        });
    }

    private record Entry<T>(T value, SharedResource<?> owner) { }

    private static final class Bucket<T> {
        final TreeMap<Long, Entry<T>> entries = new TreeMap<>();

        SharedResource<Node<T>> freeze(long key) {
            var owners = new ArrayList<SharedResource<?>>();
            var values = new ArrayList<T>(entries.size());
            for (var entry : entries.values()) {
                values.add(entry.value);
                if (entry.owner != null) owners.add(entry.owner.retain());
            }
            return SharedResource.owned(new Node<>(key, List.copyOf(values)),
                    ignored -> owners.forEach(SharedResource::close));
        }
    }

    private static final class Node<T> {
        final long key;
        final long bit;
        final int size;
        final int pageCount;
        final SharedResource<Node<T>> leftOwner;
        final SharedResource<Node<T>> rightOwner;
        final Node<T> left;
        final Node<T> right;
        final List<T> values;

        Node(long key, List<T> values) {
            this.key = key;
            this.bit = 0;
            this.size = values.size();
            this.pageCount = 1;
            this.values = values;
            leftOwner = rightOwner = null;
            left = right = null;
        }

        Node(long bit, SharedResource<Node<T>> leftOwner, SharedResource<Node<T>> rightOwner) {
            this.leftOwner = leftOwner;
            this.rightOwner = rightOwner;
            this.left = leftOwner.get();
            this.right = rightOwner.get();
            this.key = left.key;
            this.bit = bit;
            this.size = Math.addExact(left.size, right.size);
            this.pageCount = Math.addExact(left.pageCount, right.pageCount);
            this.values = null;
        }

        T get(int index) {
            if (bit == 0) return values.get(index);
            return index < left.size ? left.get(index) : right.get(index - left.size);
        }

        void appendPages(List<List<T>> pages) {
            if (bit == 0) pages.add(values);
            else {
                left.appendPages(pages);
                right.appendPages(pages);
            }
        }
    }

    /** Dense access uses subtree counts; flat page iteration is materialized only by a consumer. */
    private static final class DirectoryValues<T> extends AbstractList<T> implements SnapshotList<T> {
        final SharedResource<Node<T>> root;
        final Node<T> node;
        private volatile Values<T> iteration;

        DirectoryValues(SharedResource<Node<T>> root) {
            this.root = root;
            node = root == null ? null : root.get();
        }

        @Override public int size() { return node == null ? 0 : node.size; }

        @Override public T get(int index) {
            Objects.checkIndex(index, size());
            return node.get(index);
        }

        @Override public List<List<T>> pages() { return iteration().pages(); }

        @Override public Iterator<T> iterator() { return iteration().iterator(); }

        private Values<T> iteration() {
            Values<T> result = iteration;
            if (result != null) return result;
            synchronized (this) {
                if (iteration == null) {
                    var pages = new ArrayList<List<T>>(node == null ? 0 : node.pageCount);
                    if (node != null) node.appendPages(pages);
                    iteration = new Values<>(pages);
                }
                return iteration;
            }
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
