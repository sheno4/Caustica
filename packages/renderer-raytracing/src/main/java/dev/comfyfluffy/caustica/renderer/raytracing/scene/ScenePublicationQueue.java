package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Readiness and overlap ordering for atomic scene edits. Access is serialized by the scene owner. */
final class ScenePublicationQueue<K, V> {
    private final List<Edit<K, V>> pending = new ArrayList<>();

    Edit<K, V> add(Set<K> keys, boolean barrier, V value) {
        var edit = new Edit<>(Set.copyOf(keys), barrier, value);
        for (var previous : pending) {
            if (barrier || previous.barrier || !Collections.disjoint(previous.keys, keys)) {
                edit.dependencies.add(previous);
            }
        }
        pending.add(edit);
        return edit;
    }

    void remove(Edit<K, V> edit) { pending.remove(edit); }

    List<V> values() { return pending.stream().map(edit -> edit.value).toList(); }

    void clear() { pending.clear(); }

    void commitReady(Consumer<V> commit) {
        for (var iterator = pending.iterator(); iterator.hasNext();) {
            var edit = iterator.next();
            if (!edit.ready || edit.dependencies.stream().anyMatch(previous -> !previous.committed)) continue;
            commit.accept(edit.value);
            edit.committed = true;
            edit.dependencies.clear();
            iterator.remove();
        }
    }

    static final class Edit<K, V> {
        final Set<K> keys;
        final boolean barrier;
        final V value;
        final List<Edit<K, V>> dependencies = new ArrayList<>();
        boolean ready;
        boolean committed;
        Edit(Set<K> keys, boolean barrier, V value) {
            this.keys = keys;
            this.barrier = barrier;
            this.value = value;
        }
    }
}
