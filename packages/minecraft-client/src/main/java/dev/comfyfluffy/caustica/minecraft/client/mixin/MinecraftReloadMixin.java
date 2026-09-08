package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

/**
 * Detaches RT from the current resource pack before Minecraft replaces its images. The returned
 * reload future makes the replacement pack available to RT on a subsequent client tick.
 * Only the public no-argument reload entry point is wrapped; startup loading uses a separate overload.
 */
@Mixin(Minecraft.class)
public class MinecraftReloadMixin {
    @WrapMethod(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Void> caustica$reloadResourcePacks(Operation<CompletableFuture<Void>> original) {
        var runtime = CausticaClientComposition.current().runtime();
        long generation = runtime.beginResourcePackReload();
        CompletableFuture<Void> result = original.call();
        runtime.trackResourcePackReload(generation, result);
        return result;
    }
}
