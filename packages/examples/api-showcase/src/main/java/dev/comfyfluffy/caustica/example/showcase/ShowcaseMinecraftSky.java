package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.Objects;

/** Selects Minecraft-owned sky content from the dimension fixed by one world-session epoch. */
final class ShowcaseMinecraftSky {
    private static final MinecraftDimensionKey NETHER = MinecraftDimensionKey.of("minecraft", "the_nether");
    private static final MinecraftDimensionKey END = MinecraftDimensionKey.of("minecraft", "the_end");

    private final MinecraftDimensionKey dimension;
    private final ResourcePackEpoch resources;

    ShowcaseMinecraftSky(MinecraftDimensionKey dimension, ResourcePackEpoch resources) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    EnvironmentBinding<ShowcasePrograms.EnvironmentBindingData> binding(ShowcasePrograms programs) {
        return programs.environmentBinding(implementation(programs.exports()), bindingWord());
    }

    long bindingWord() {
        return resources.generation();
    }

    EnvironmentId<ShowcasePrograms.EnvironmentBindingData> implementation(ShowcasePrograms.Exports exports) {
        if (dimension.equals(NETHER)) return exports.netherSky();
        if (dimension.equals(END)) return exports.endSky();
        return exports.overworldSky();
    }
}
