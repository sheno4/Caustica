package dev.comfyfluffy.caustica.minecraft.client.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Retain chunk residency while graph traversal and section propagation are suspended. */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {
    @Accessor("loadedChunks")
    LongOpenHashSet caustica$getLoadedChunks();

    @Accessor("emptySections")
    LongOpenHashSet caustica$getEmptySections();
}
