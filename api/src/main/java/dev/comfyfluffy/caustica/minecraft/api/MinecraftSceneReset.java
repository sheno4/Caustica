package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.provider.SceneScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minecraft host seam for clearing source-local world state before resetting retained engine geometry. */
public final class MinecraftSceneReset {
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private MinecraftSceneReset() { }

    /** Registers one activation-scoped local reset and its retained scene scope. */
    public static Registration register(SceneScope scope, Runnable resetAction) {
        Entry entry = new Entry(Objects.requireNonNull(scope, "scope"),
                Objects.requireNonNull(resetAction, "resetAction"));
        synchronized (ENTRIES) {
            ENTRIES.add(entry);
        }
        return () -> {
            synchronized (ENTRIES) {
                ENTRIES.remove(entry);
            }
        };
    }

    /** Clears every active Minecraft-aware source before requesting the coalesced engine reset. */
    public static void request() {
        List<Entry> entries;
        synchronized (ENTRIES) {
            entries = List.copyOf(ENTRIES);
        }
        entries.forEach(entry -> entry.resetAction.run());
        entries.forEach(entry -> entry.scope.requestSceneReset());
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Entry {
        private final SceneScope scope;
        private final Runnable resetAction;

        private Entry(SceneScope scope, Runnable resetAction) {
            this.scope = scope;
            this.resetAction = resetAction;
        }
    }
}
