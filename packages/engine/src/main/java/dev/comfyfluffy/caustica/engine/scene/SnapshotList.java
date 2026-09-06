package dev.comfyfluffy.caustica.engine.scene;

import java.util.List;

/** Immutable dense values backed by shared, nonempty pages in iteration order. */
public interface SnapshotList<T> extends List<T> {
    /** An unchanged page keeps its list identity across captures; its values borrow the capture's ownership. */
    List<List<T>> pages();

    /** Concatenates immutable, nonempty pages without copying their values. */
    static <T> SnapshotList<T> ofPages(List<List<T>> pages) {
        return new SnapshotPages.Values<>(pages);
    }

    /** A plain immutable list is a single page; empty lists contain no pages. */
    @SuppressWarnings("unchecked")
    static <T> List<List<T>> pagesOf(List<T> values) {
        return values instanceof SnapshotList<?> paged ? ((SnapshotList<T>) paged).pages()
                : values.isEmpty() ? List.of() : List.of(values);
    }
}
