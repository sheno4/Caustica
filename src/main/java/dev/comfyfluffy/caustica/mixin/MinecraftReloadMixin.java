package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Release RT references before an actual reload, including a resource-pack rollback. */
@Mixin(Minecraft.class)
public class MinecraftReloadMixin {
    @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;"
                    + "createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;"
                    + "Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)"
                    + "Lnet/minecraft/server/packs/resources/ReloadInstance;"))
    private void caustica$rtReloadStart(boolean recovery, GameLoadCookie cookie,
                                        CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        RtComposite.INSTANCE.onResourceReloadStart();
    }
}
