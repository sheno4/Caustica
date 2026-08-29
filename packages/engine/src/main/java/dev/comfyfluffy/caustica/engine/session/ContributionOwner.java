package dev.comfyfluffy.caustica.engine.session;

/** Engine identity used to create one owner-scoped set of render-session services. */
public final class ContributionOwner {
    private final long sequence;

    public ContributionOwner(long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        this.sequence = sequence;
    }

    /** Monotonic identity useful for diagnostics. It has no meaning outside its render session. */
    public long sequence() {
        return sequence;
    }
}
