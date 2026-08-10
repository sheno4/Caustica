package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.FeatureCategory;
import dev.comfyfluffy.caustica.api.FeatureBuilder;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;

/** Registers the renderer-owned reference composition without installing any host scene sources. */
public final class BuiltinExtension implements CausticaExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "builtin");
    /** The reference surface, and the index-0 fallback of the per-material dispatch. */
    public static final ResourceId BUILTIN_SURFACE = ResourceId.of("caustica", "surface");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.translatable("feature.caustica.builtin"))
                .category(FeatureCategory.GENERAL)
                .shaderSource(ShaderSource.classpath(
                        "/caustica/shaders/builtin", "sky", "surface", "bloom"))
                .bind(Slots.SKY, "caustica_builtin_sky", "BuiltinSky")
                .surface(BUILTIN_SURFACE, "caustica_builtin_surface", "BuiltinSurface")
                .group(BloomPass.GROUP)
                .options(BloomPass.OPTIONS)
                .renderPass(new BloomPass())
                .register();
        registry.setDefault(Slots.SKY, ID);
    }
}
