package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.FeatureCategory;
import dev.comfyfluffy.caustica.api.FeatureBuilder;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.RuntimeActivation;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.Slots;
import dev.comfyfluffy.caustica.api.pass.RenderStage;

/** Registers core renderer effects and the diagnostic material implementation. */
public final class BuiltinExtension implements CausticaExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "builtin");
    /** The visible failure surface at reserved implementation index 0. */
    public static final ResourceId ERROR_SURFACE = ResourceId.of("caustica", "error_surface");
    /** Conservative failure coverage paired with {@link #ERROR_SURFACE}. */
    public static final ResourceId ERROR_COVERAGE = ResourceId.of("caustica", "error_coverage");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(DisplayText.translatable("feature.caustica.builtin"))
                .description(DisplayText.translatable("feature.caustica.builtin.description"))
                .category(FeatureCategory.GENERAL)
                .shaderSource(ShaderSource.classpath(
                        "/caustica/shaders/builtin", "sky", "surface", "bloom"))
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .bind(Slots.SKY, "caustica_builtin_sky", "BuiltinSky")
                .surface(ERROR_SURFACE, "caustica_error_surface", "ErrorSurface",
                        ERROR_COVERAGE, "caustica_error_coverage", "ErrorCoverage")
                .group(BloomPass.GROUP)
                .options(BloomPass.OPTIONS)
                .renderPass(BloomPass.ID, RenderStage.AFTER_RECONSTRUCTION, BloomPass::new)
                .register();
        registry.setDefault(Slots.SKY, ID);
    }
}
