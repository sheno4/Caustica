package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkSkyLightSources.class)
abstract class ChunkSkyLightSourcesMixin {
    @Unique private static final EventType CAUSTICA_SKYLIGHT_EVENT = EventType.getEventType(SkylightEvent.class);

    @WrapMethod(method = "fillFrom")
    private void caustica$measureInitialization(ChunkAccess chunk, Operation<Void> original) {
        if (!CAUSTICA_SKYLIGHT_EVENT.isEnabled()) {
            original.call(chunk);
            return;
        }
        SkylightEvent event = new SkylightEvent();
        event.chunkX = chunk.getPos().x();
        event.chunkZ = chunk.getPos().z();
        event.begin();
        try {
            original.call(chunk);
        } finally {
            event.end();
            event.commit();
        }
    }

    @Name("dev.comfyfluffy.caustica.ChunkSkylight")
    @Label("Chunk skylight source initialization")
    @Category({"Caustica", "Host"})
    @Enabled(false)
    @StackTrace(false)
    public static final class SkylightEvent extends Event {
        public int chunkX;
        public int chunkZ;
    }
}
