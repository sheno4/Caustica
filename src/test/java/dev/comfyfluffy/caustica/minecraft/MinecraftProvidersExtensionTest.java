package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProvidersExtensionTest {
    @Test
    void minecraftOwnsHostStateRenderPasses() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new MinecraftProvidersExtension().register(registry);

        assertTrue(registry.renderPasses().containsKey(ResourceId.of("caustica", "sky_lut")));
        assertTrue(registry.renderPasses().containsKey(ResourceId.of("caustica", "world_overlay")));
        assertTrue(registry.features().get(MinecraftProvidersExtension.ID).options()
                .contains(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES));
    }
}
