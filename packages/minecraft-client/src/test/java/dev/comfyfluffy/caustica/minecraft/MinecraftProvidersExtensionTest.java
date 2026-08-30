package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProvidersExtensionTest {
    @Test
    void builtinAndMinecraftSettingsUseTheIndependentSettingsRegistry() {
        SettingsRegistry settings = new SettingsRegistry();
        new BuiltinExtension().registerSettings(settings);
        extension().registerSettings(settings);

        assertTrue(settings.declared(BuiltinExtension.ID));
        assertTrue(settings.declared(MinecraftProvidersExtension.ID));
        assertEquals(SkyLutPass.OPTIONS, settings.settings(MinecraftProvidersExtension.ID).options());
    }

    @Test
    void installsExactlyOneCoreMinecraftWorldSessionFactory() {
        List<MinecraftWorldSessionFactory> factories = new ArrayList<>();
        OptionLookup options = id -> { throw new AssertionError(id); };
        MinecraftApi api = new MinecraftApi(factory -> {
            factories.add(factory);
            return () -> { };
        }, options);

        extension().registerMinecraft(api);

        assertEquals(1, factories.size());
        org.junit.jupiter.api.Assertions.assertSame(options, api.options());
    }

    private static MinecraftProvidersExtension extension() {
        MinecraftMaterialEpochCompiler materials = (epoch, rules) -> null;
        var textures = new dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures();
        var entities = new dev.comfyfluffy.caustica.minecraft.entity.RtEntities(
                textures, MinecraftTelemetry.disabled());
        return new MinecraftProvidersExtension(selector -> () -> { }, (sink, calibration) -> () -> { },
                materials, new MinecraftLightingCalibration(1, 1, 1, 0, 0, 0),
                entities, textures, entities,
                new RtTerrain(new RtWorkerPool(), MinecraftTelemetry.disabled()),
                MinecraftTelemetry.disabled());
    }
}
