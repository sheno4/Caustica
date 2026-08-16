package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.damage.MinecraftDamageModifierPass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftProvidersExtensionTest {
    @Test
    void minecraftOwnsHostStateRenderPasses() {
        CausticaRegistry registry = new CausticaRegistry();
        new BuiltinExtension().register(registry);
        new MinecraftProvidersExtension().register(registry);

        assertTrue(registry.renderPassIds().contains(ResourceId.of("caustica", "sky_lut")));
        assertTrue(registry.renderPassIds().contains(ResourceId.of("caustica", "world_overlay")));
        assertTrue(registry.renderPassIds().contains(MinecraftDamageModifierPass.ID));
        assertTrue(registry.features().get(MinecraftProvidersExtension.ID).options()
                .contains(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES));
        assertEquals("MinecraftOverworldSky",
                registry.features().get(MinecraftProvidersExtension.ID).bindings().get(Slots.SKY).type());
        assertTrue(registry.features().get(MinecraftProvidersExtension.ID).passResourceModules()
                .contains("caustica_minecraft_sky_bindings"));
        assertTrue(registry.features().get(MinecraftProvidersExtension.ID).passResourceModules()
                .contains("caustica_minecraft_damage_bindings"));
        assertTrue(registry.surfaceModifiers().stream()
                .anyMatch(modifier -> modifier.id().equals(MinecraftDamageModifierPass.MODIFIER_ID)
                        && modifier.module().equals("caustica_minecraft_damage_modifier")
                        && modifier.type().equals("MinecraftDamageModifier")));
        assertTrue(registry.surfaces().stream()
                .anyMatch(surface -> surface.id().equals(MinecraftProvidersExtension.WATER_SURFACE)
                        && surface.module().equals("caustica_water_surface")
                        && surface.type().equals("WaterSurface")));
        assertTrue(registry.surfaces().stream()
                .anyMatch(surface -> surface.id().equals(MinecraftProvidersExtension.END_PORTAL_SURFACE)
                        && surface.module().equals("caustica_portal_surface")
                        && surface.type().equals("PortalSurface")));
    }
}
