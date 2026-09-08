package dev.comfyfluffy.caustica.minecraft.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Vanilla terrain resumes from a fresh camera-centered rebuild after RT has replaced it. */
public final class VanillaTerrainSuspension {
    private boolean suspended;

    VanillaTerrainSuspension() { }

    boolean suspended() { return suspended; }

    void suspend(Runnable settleGraph) {
        if (suspended) return;
        settleGraph.run();
        suspended = true;
    }

    void resume(boolean rtOwnsWorld, Runnable rebuild) {
        if (!suspended || rtOwnsWorld) return;
        rebuild.run();
        suspended = false;
    }

    void reset() { suspended = false; }

    /** Match vanilla's additions-before-removals semantics without queuing graph propagation. */
    public static void applyDelta(LongOpenHashSet retained, LongOpenHashSet added, LongOpenHashSet removed) {
        retained.addAll(added);
        retained.removeAll(removed);
    }
}
