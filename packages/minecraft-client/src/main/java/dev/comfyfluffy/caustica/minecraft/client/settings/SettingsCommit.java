package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.config.CausticaOptions;

/**
 * Decides when edits reach disk. Every control writes in memory, so the renderer sees a change on the next
 * frame while a dragged slider never touches the filesystem; this writes both stores once, when the screen
 * closes.
 *
 * <p>A crash before then loses the session's edits, which matches what vanilla's own options screens do.
 */
public final class SettingsCommit {
    private final CausticaOptions options;

    public SettingsCommit(CausticaOptions options) {
        this.options = options;
    }

    /**
     * Both stores, each a no-op when nothing changed: {@code CausticaOptions} tracks its own pending writes,
     * and {@code CausticaConfig.save()} rewrites the whole surface, which is cheap and idempotent.
     */
    public void save() {
        options.save();
        CausticaConfig.save();
    }
}
