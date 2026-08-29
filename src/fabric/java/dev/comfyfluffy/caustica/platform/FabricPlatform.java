package dev.comfyfluffy.caustica.platform;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric implementation of the shared loader services. */
public final class FabricPlatform implements CausticaPlatform {
    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public List<CausticaExtension> extensions() {
        return FabricLoader.getInstance().getEntrypoints("caustica", CausticaExtension.class);
    }

    @Override
    public List<MinecraftExtension> minecraftExtensions() {
        return FabricLoader.getInstance().getEntrypoints(MinecraftApi.ENTRYPOINT, MinecraftExtension.class);
    }
}
