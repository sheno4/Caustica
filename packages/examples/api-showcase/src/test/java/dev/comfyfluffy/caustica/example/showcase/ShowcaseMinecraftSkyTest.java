package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShowcaseMinecraftSkyTest {
    @Test
    void skySelectionIsMinecraftDimensionOwned() {
        EnvironmentId<ShowcasePrograms.EnvironmentBindingData> overworld = new EnvironmentId<>() { };
        EnvironmentId<ShowcasePrograms.EnvironmentBindingData> nether = new EnvironmentId<>() { };
        EnvironmentId<ShowcasePrograms.EnvironmentBindingData> end = new EnvironmentId<>() { };
        var exports = new ShowcasePrograms.Exports(null, null, null, overworld, nether, end);

        ResourcePackEpoch resources = new ResourcePackEpoch(7L);
        assertEquals(7L, new ShowcaseMinecraftSky(
                MinecraftDimensionKey.of("minecraft", "overworld"), resources).bindingWord());
        assertSame(overworld, new ShowcaseMinecraftSky(
                MinecraftDimensionKey.of("minecraft", "overworld"), resources).implementation(exports));
        assertSame(nether, new ShowcaseMinecraftSky(
                MinecraftDimensionKey.of("minecraft", "the_nether"), resources).implementation(exports));
        assertSame(end, new ShowcaseMinecraftSky(
                MinecraftDimensionKey.of("minecraft", "the_end"), resources).implementation(exports));
        assertSame(overworld, new ShowcaseMinecraftSky(
                MinecraftDimensionKey.of("example", "custom"), resources).implementation(exports));
    }
}
