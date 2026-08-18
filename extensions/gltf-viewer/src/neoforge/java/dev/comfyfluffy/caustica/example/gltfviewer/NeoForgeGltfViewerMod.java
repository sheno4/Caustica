package dev.comfyfluffy.caustica.example.gltfviewer;

import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge content-registration entrypoint for the standalone extension. */
@Mod(value = GltfViewerMod.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeGltfViewerMod {
    public NeoForgeGltfViewerMod(IEventBus modBus) {
        modBus.addListener(this::registerItems);
        modBus.addListener(this::registerBlocks);
        modBus.addListener(this::registerBlockEntities);
    }

    private void registerItems(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> GltfViewerItems.register(helper::register));
    }

    private void registerBlocks(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> GltfViewerBlocks.registerBlocks(helper::register));
    }

    private void registerBlockEntities(RegisterEvent event) {
        event.register(Registries.BLOCK_ENTITY_TYPE,
                helper -> GltfViewerBlocks.registerBlockEntities(helper::register));
    }
}
