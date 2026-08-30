package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.mixin.DebugScreenEntriesAccessor;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * F3 line for the RT auto-exposure controller. Off by default like any other optional entry
 * (Vanilla's own per-player {@code debug-profile.json}, toggled through the F3 entry list) --
 * registration only makes it available, it does not turn it on.
 *
 * <p>The renderer diagnostics contract owns the displayed controller values.
 */
public final class RtExposureDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = DebugScreenEntriesAccessor.caustica$register(
            Identifier.fromNamespaceAndPath("caustica", "rt_exposure"), new RtExposureDebugEntry());

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel,
                        @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        if (CausticaClientComposition.current().runtime().rendererFailed()) {
            return; // vanilla is rendering this frame; the exposure state is stale/irrelevant.
        }
        String line = CausticaClientComposition.current().runtime().exposureSummary();
        if (line != null) {
            displayer.addLine(line);
        }
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return !reducedDebugInfo;
    }
}
