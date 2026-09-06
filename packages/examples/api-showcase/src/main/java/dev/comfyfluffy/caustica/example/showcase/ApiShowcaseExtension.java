package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.SettingsAccess;

/** Compile-only consumer of every Vulkan-native API feature category. */
public final class ApiShowcaseExtension implements MinecraftExtension, CausticaSettingsExtension {
    static final ResourceId ID = ResourceId.of("caustica_showcase", "rendering");
    static final Option<Float> COLOUR_GRADE_STRENGTH =
            Option.range("colour_grade_strength", 0.0f, 1.0f, 0.5f).step(0.05);

    private SettingsAccess options;

    @Override
    public void settingsReady(SettingsAccess settings) {
        options = settings;
    }

    @Override
    public void registerMinecraft(MinecraftApi api) {
        ShowcaseHandoff handoff = new ShowcaseHandoff();
        api.sessions().add(context -> new ShowcaseSelectionContribution(context, handoff));
        api.sessions().add(context -> new ShowcaseSession(
                context, options, handoff.require(context.scene())));
    }

    @Override
    public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.literal("API showcase"))
                .option(COLOUR_GRADE_STRENGTH).register();
    }
}
