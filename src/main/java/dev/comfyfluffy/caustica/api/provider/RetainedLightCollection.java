package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.List;
import java.util.Objects;

/**
 * Complete immutable retained finite-light state for one provider. The collection generation changes
 * whenever group membership or any group revision changes, allowing unchanged frames to reuse it in O(1).
 */
public record RetainedLightCollection(long generation, List<Group> groups) {
    public static final RetainedLightCollection EMPTY = new RetainedLightCollection(0L, List.of());

    public RetainedLightCollection {
        groups = List.copyOf(groups);
    }

    /** One source-local retained-light group. Its revision changes whenever its lights change. */
    public record Group(long key, long revision, List<LightDescriptor.Finite> lights) {
        public Group {
            Objects.requireNonNull(lights, "lights");
            lights = List.copyOf(lights);
        }
    }
}
