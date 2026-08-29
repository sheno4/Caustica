package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.builtin.BuiltinExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
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
        new MinecraftProvidersExtension(selector -> () -> { }, sink -> () -> { }).registerSettings(settings);

        assertTrue(settings.declared(BuiltinExtension.ID));
        assertTrue(settings.declared(MinecraftProvidersExtension.ID));
        assertEquals(SkyLutPass.OPTIONS, settings.settings(MinecraftProvidersExtension.ID).options());
    }

    @Test
    void installsExactlyOneCoreMinecraftWorldSessionFactory() {
        List<MinecraftWorldSessionFactory> factories = new ArrayList<>();
        MinecraftApi api = new MinecraftApi(factory -> {
            factories.add(factory);
            return () -> { };
        });

        new MinecraftProvidersExtension(selector -> () -> { }, sink -> () -> { }).registerMinecraft(api);

        assertEquals(1, factories.size());
    }
}
