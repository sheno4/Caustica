package dev.comfyfluffy.caustica.minecraft.client.platform;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import net.neoforged.fml.loading.FMLPaths;

/** NeoForge implementation of the shared loader services. */
public final class NeoForgePlatform implements CausticaPlatform {
    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public List<CausticaExtension> extensions() {
        // Bind the lookup to the class loader that owns the API type. The thread context class loader
        // during FML mod construction is not guaranteed to see other mods' service declarations.
        return ServiceLoader.load(CausticaExtension.class, CausticaExtension.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    @Override
    public List<MinecraftExtension> minecraftExtensions() {
        return ServiceLoader.load(MinecraftExtension.class, MinecraftExtension.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }
}
