package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.client.CausticaClientBootstrap;
import dev.comfyfluffy.caustica.minecraft.CausticaItems;
import dev.comfyfluffy.caustica.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.platform.NeoForgePlatform;
import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge entrypoint for the shared Caustica initialization. */
@Mod(value = CausticaMod.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeCausticaMod {
    public NeoForgeCausticaMod(IEventBus modBus) {
        CausticaPlatform.install(new NeoForgePlatform());
        modBus.addListener(this::registerItems);
        modBus.addListener(this::clientSetup);
        CausticaMod.initialize();
    }

    // Mod constructors run in parallel; client bootstrap registers a vanilla debug-screen entry into a
    // plain HashMap, so it has to run on the single-threaded setup phase instead.
    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(CausticaClientBootstrap::initialize);
    }

    private void registerItems(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> CausticaItems.register(
                helper::register));
    }
}
