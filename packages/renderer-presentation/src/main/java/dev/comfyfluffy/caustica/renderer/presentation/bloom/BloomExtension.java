package dev.comfyfluffy.caustica.renderer.presentation.bloom;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.FeatureCategory;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsAccess;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

/** Registers Bloom settings and one post-effect pass per render session. */
public final class BloomExtension implements CausticaExtension, CausticaSettingsExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "bloom");

    private SettingsAccess options;

    @Override
    public void settingsReady(SettingsAccess settings) {
        options = settings;
    }

    @Override
    public void register(CausticaApi api) {
        api.sessions().add(context -> {
            var bloom = context.passes().addPostEffectPass(BloomPass.ID,
                    setup -> new BloomPass(setup, context.resources(), () -> options.snapshot().options(ID)));
            return new RenderSessionContribution() {
                @Override
                public void stop() {
                    bloom.close();
                }
            };
        });
    }

    @Override
    public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.translatable("feature.caustica.bloom"))
                .description(DisplayText.translatable("feature.caustica.bloom.description"))
                .category(FeatureCategory.GENERAL)
                .group(BloomPass.GROUP)
                .options(BloomPass.OPTIONS)
                .register();
    }
}
