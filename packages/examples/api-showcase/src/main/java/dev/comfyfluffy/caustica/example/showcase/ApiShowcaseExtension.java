package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

/** Compile-only consumer of every Vulkan-native API feature category. */
public final class ApiShowcaseExtension implements CausticaExtension, CausticaSettingsExtension {
    static final ResourceId ID = ResourceId.of("caustica_showcase", "rendering");
    static final Option<Float> COLOUR_GRADE_STRENGTH =
            Option.range("colour_grade_strength", 0.0f, 1.0f, 0.5f).step(0.05);

    @Override
    public void register(CausticaApi api) {
        api.sessions().add(context -> new ShowcaseSession(context, api.options()));
    }

    @Override
    public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.literal("API showcase"))
                .option(COLOUR_GRADE_STRENGTH).register();
    }
}
