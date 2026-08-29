package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.light.LightId;

import java.util.List;
import java.util.Objects;

/**
 * Sparse placement-local association from global mesh triangle numbers to retained emitters.
 *
 * <p>The same mesh can be placed with different maps because retained light identities belong to a scene,
 * not to reusable mesh data. A range is a non-owning selection reference: a light issued by another
 * contribution in the same render session is valid, but this map grants no mutation authority and does not
 * keep the light alive. A referenced light which is absent or belongs to another scene is non-sampleable.
 *
 * <p>Each range must cover its visible emitter proxy accurately. The renderer uses that association to
 * evaluate the reverse light-sampling PDF when a BSDF-sampled ray hits emissive geometry.
 */
public record PrimitiveLightMap(List<Range> ranges) {
    public static final PrimitiveLightMap EMPTY = new PrimitiveLightMap(List.of());

    public PrimitiveLightMap {
        ranges = List.copyOf(ranges);
        long previousEnd = 0;
        for (Range range : ranges) {
            if (range.firstPrimitive() < previousEnd) {
                throw new IllegalArgumentException("primitive-light ranges must be sorted and disjoint");
            }
            previousEnd = Math.addExact((long) range.firstPrimitive(), range.primitiveCount());
        }
    }

    public record Range(int firstPrimitive, int primitiveCount, LightId light) {
        public Range {
            if (firstPrimitive < 0) throw new IllegalArgumentException("firstPrimitive must be non-negative");
            if (primitiveCount <= 0) throw new IllegalArgumentException("primitiveCount must be positive");
            Objects.requireNonNull(light, "light");
            Math.addExact(firstPrimitive, primitiveCount);
        }
    }
}
