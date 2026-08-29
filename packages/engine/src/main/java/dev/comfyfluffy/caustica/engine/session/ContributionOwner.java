package dev.comfyfluffy.caustica.engine.session;

/** Engine identity used to create one owner-scoped set of render-session services. */
public final class ContributionOwner {
    private final long sequence;

    ContributionOwner(long sequence) {
        this.sequence = sequence;
    }

    /** Monotonic identity useful for diagnostics. It has no meaning outside its render session. */
    public long sequence() {
        return sequence;
    }
}
