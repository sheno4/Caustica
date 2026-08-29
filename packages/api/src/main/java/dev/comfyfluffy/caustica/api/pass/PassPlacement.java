package dev.comfyfluffy.caustica.api.pass;

import java.util.Objects;

/**
 * One optional stage-local ordering relationship for an addressable post or UI pass. A missing anchor
 * leaves the pass unconstrained until that anchor is registered. The registration that makes a relationship
 * cycle is rejected, whether it declares the relationship or activates one that was previously missing.
 */
public sealed interface PassPlacement permits PassPlacement.Before, PassPlacement.After {
    PassId anchor();

    static PassPlacement before(PassId anchor) {
        return new Before(anchor);
    }

    static PassPlacement after(PassId anchor) {
        return new After(anchor);
    }

    /** Places the registered pass before {@link #anchor()} when that pass is present in the same stage. */
    record Before(PassId anchor) implements PassPlacement {
        public Before {
            Objects.requireNonNull(anchor, "anchor");
        }
    }

    /** Places the registered pass after {@link #anchor()} when that pass is present in the same stage. */
    record After(PassId anchor) implements PassPlacement {
        public After {
            Objects.requireNonNull(anchor, "anchor");
        }
    }
}
