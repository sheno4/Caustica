package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneGeometryDelta;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Mutable accepted-tail instance state used while preparing one atomic geometry publication. */
final class RtRetainedInstanceState {
    private final Map<Long, RetainedSceneSnapshot.Instance> instances = new LinkedHashMap<>();
    private final Map<Long, GeometryTransform> previousTransforms = new LinkedHashMap<>();
    private final Map<Long, Long> ordinals = new LinkedHashMap<>();

    void retain(RetainedSceneSnapshot.Instance instance, long ordinal) {
        instances.put(instance.identity(), instance);
        previousTransforms.put(instance.identity(), instance.transform());
        ordinals.put(instance.identity(), ordinal);
    }

    void apply(List<RetainedSceneGeometryDelta.Mutation> mutations, LongSupplier nextOrdinal) {
        for (RetainedSceneGeometryDelta.Mutation mutation : mutations) {
            if (mutation instanceof RetainedSceneGeometryDelta.DropMesh drop) {
                List<Long> removed = instances.entrySet().stream()
                        .filter(entry -> entry.getValue().meshIdentity() == drop.identity())
                        .map(Map.Entry::getKey)
                        .toList();
                removed.forEach(this::drop);
            } else if (mutation instanceof RetainedSceneGeometryDelta.SetInstance set) {
                long identity = set.instance().identity();
                if (!ordinals.containsKey(identity)) ordinals.put(identity, nextOrdinal.getAsLong());
                instances.put(identity, set.instance());
            } else if (mutation instanceof RetainedSceneGeometryDelta.DropInstance drop) {
                drop(drop.identity());
            }
        }
    }

    /** Replace the whole table, keeping ordinals and motion history for surviving identities. */
    void replaceAll(List<RetainedSceneSnapshot.Instance> next, LongSupplier nextOrdinal) {
        Map<Long, RetainedSceneSnapshot.Instance> replacement = new LinkedHashMap<>();
        for (RetainedSceneSnapshot.Instance instance : next) {
            long identity = instance.identity();
            if (!ordinals.containsKey(identity)) {
                ordinals.put(identity, nextOrdinal.getAsLong());
                previousTransforms.put(identity, instance.transform());
            }
            replacement.put(identity, instance);
        }
        ordinals.keySet().retainAll(replacement.keySet());
        previousTransforms.keySet().retainAll(replacement.keySet());
        instances.clear();
        instances.putAll(replacement);
    }

    /**
     * Adopt every current transform as the motion-vector predecessor. Called once per materialized layout,
     * so "previous" always names the placement the last rendered layout used rather than the last accepted
     * mutation, which may not have reached a frame.
     */
    void latchPreviousTransforms() {
        instances.forEach((identity, instance) -> previousTransforms.put(identity, instance.transform()));
    }

    java.util.Set<Long> identities() {
        return java.util.Set.copyOf(instances.keySet());
    }

    List<RetainedSceneSnapshot.Instance> orderedInstances() {
        List<RetainedSceneSnapshot.Instance> ordered = new ArrayList<>(instances.values());
        ordered.sort(Comparator.comparingLong(instance -> ordinal(instance.identity())));
        return List.copyOf(ordered);
    }

    GeometryTransform previousTransform(RetainedSceneSnapshot.Instance instance) {
        return previousTransforms.getOrDefault(instance.identity(), instance.transform());
    }

    long ordinal(long identity) {
        return ordinals.get(identity);
    }

    private void drop(long identity) {
        instances.remove(identity);
        previousTransforms.remove(identity);
        ordinals.remove(identity);
    }
}
