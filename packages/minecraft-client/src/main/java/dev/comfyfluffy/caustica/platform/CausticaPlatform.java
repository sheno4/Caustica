package dev.comfyfluffy.caustica.platform;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import java.nio.file.Path;
import java.util.List;

/** Loader services consumed by the shared Caustica runtime. */
public interface CausticaPlatform {
    Path gameDir();

    Path configDir();

    List<CausticaExtension> extensions();

    List<MinecraftExtension> minecraftExtensions();

}
