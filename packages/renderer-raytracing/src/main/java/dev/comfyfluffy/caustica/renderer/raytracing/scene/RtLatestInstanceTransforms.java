package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedInstanceTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneGeometryDelta;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Latest accepted rigid placements applied independently of ordered mesh publication. */
final class RtLatestInstanceTransforms {
    private Map<Long, State> states = new HashMap<>();

    void acceptSnapshot(List<RetainedSceneSnapshot.Instance> instances) {
        apply(prepareSnapshot(instances));
    }

    void acceptMutations(List<RetainedSceneGeometryDelta.Mutation> mutations) {
        apply(prepareMutations(mutations));
    }

    Update prepareSnapshot(List<RetainedSceneSnapshot.Instance> instances) {
        Map<Long, State> next = copyStates();
        for (RetainedSceneSnapshot.Instance instance : instances) acceptOrdered(next, instance);
        return new Update(next);
    }

    Update prepareMutations(List<RetainedSceneGeometryDelta.Mutation> mutations) {
        Map<Long, State> next = copyStates();
        Set<Long> dropped = new HashSet<>();
        for (RetainedSceneGeometryDelta.Mutation mutation : mutations) {
            if (mutation instanceof RetainedSceneGeometryDelta.DropInstance drop) {
                dropped.add(drop.identity());
            } else if (mutation instanceof RetainedSceneGeometryDelta.SetInstance set) {
                if (dropped.remove(set.instance().identity())) {
                    RetainedSceneSnapshot.Instance instance = set.instance();
                    next.put(instance.identity(), new State(
                            instance.transform(), instance.mask(), false));
                } else {
                    acceptOrdered(next, set.instance());
                }
            }
        }
        return new Update(next);
    }

    void apply(Update update) {
        states = update.states;
    }

    void acceptLatest(List<RetainedInstanceTransform> transforms) {
        for (RetainedInstanceTransform transform : transforms) {
            State state = states.get(transform.identity());
            if (state == null) {
                states.put(transform.identity(), new State(
                        transform.transform(), transform.mask(), true));
                continue;
            }
            state.current = transform.transform();
            state.mask = transform.mask();
            state.active = true;
        }
    }

    RetainedSceneSnapshot.Instance resolve(RetainedSceneSnapshot.Instance instance) {
        State state = states.get(instance.identity());
        if (state == null || !state.active) return instance;
        return new RetainedSceneSnapshot.Instance(
                instance.identity(), instance.scene(), instance.meshIdentity(), state.current, state.mask,
                instance.instanceData(), instance.primitiveEmitters());
    }

    void retainOnly(Set<Long> identities) {
        states.keySet().retainAll(identities);
    }

    private Map<Long, State> copyStates() {
        Map<Long, State> copy = new HashMap<>();
        states.forEach((identity, state) -> copy.put(identity,
                new State(state.current, state.mask, state.active)));
        return copy;
    }

    private static void acceptOrdered(Map<Long, State> states, RetainedSceneSnapshot.Instance instance) {
        State prior = states.get(instance.identity());
        if (prior == null) {
            states.put(instance.identity(), new State(
                    instance.transform(), instance.mask(), false));
            return;
        }
        if (!prior.current.equals(instance.transform()) || prior.mask != instance.mask()) {
            prior.current = instance.transform();
            prior.mask = instance.mask();
            prior.active = false;
        }
    }

    record Update(Map<Long, State> states) { }

    private static final class State {
        GeometryTransform current;
        int mask;
        boolean active;

        State(GeometryTransform current, int mask, boolean active) {
            this.current = current;
            this.mask = mask;
            this.active = active;
        }
    }
}
