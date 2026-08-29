package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftProgramSession;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

/** Installs Caustica's core Minecraft world-session contribution and settings feature. */
public final class MinecraftProvidersExtension implements MinecraftExtension, CausticaSettingsExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft");

    @Override public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(MinecraftProgramSession::open);
    }

    @Override public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.literal("Minecraft"))
                .group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS).register();
    }
}
