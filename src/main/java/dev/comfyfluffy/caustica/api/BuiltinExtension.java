package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.builtin.BloomPass;
import dev.comfyfluffy.caustica.builtin.SkyLutPass;
import dev.comfyfluffy.caustica.builtin.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.rt.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.rt.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.rt.provider.MinecraftSceneProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class BuiltinExtension implements CausticaExtension {
    static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "builtin");

    @Override
    public void register(CausticaRegistry registry) {
        // One instance registered under both mechanisms: it is a render pass (bakes the sky LUTs) and,
        // separately, a light provider (submits the sun/moon it just computed) — see SkyLutPass's javadoc.
        SkyLutPass skyLut = new SkyLutPass();
        registry.feature(ID)
                .title(Component.translatable("feature.caustica.builtin"))
                .category(FeatureCategory.GENERAL)
                .shaderSource(ShaderSource.classpath("/caustica/shaders/builtin", "sky", "common"))
                .bind(Slots.SKY, "caustica_lut_sky", "LutSky")
                .bind(Slots.SURFACE, "caustica_builtin_surface", "BuiltinSurface")
                .bind(Slots.MEDIUM, "caustica_builtin_medium", "BuiltinMedium")
                // Anchors SkyLutPass's own binding declarations outside the generic Sky-slot mechanism —
                // Slang forbids a slot IMPLEMENTATION from declaring global shader parameters itself. See
                // FeatureBuilder.passResourceModule's javadoc.
                .passResourceModule("caustica_lut_sky_bindings")
                // Bloom and sky used to live in look.json (the versioned exposure/LMT/photometric-anchor
                // package): unlike those, neither is a color-science calibration that has to move in lock
                // step with the LMT, so they belong here instead — extension-owned options a pass reads
                // through PassSetup/PassFrame, exercising the seam PassOptions was built for. Defaults and
                // ranges are unchanged from the old look.json schema 4 "bloom"/"sky" sections.
                .option(Option.range("bloom.strength", 0.0f, 2.0f, 0.02f))
                .option(Option.range("bloom.threshold-scene-linear", 0.0f, 65504.0f, 2.0f))
                .option(Option.range("bloom.soft-knee-fraction", 0.0f, 1.0f, 0.25f))
                .option(Option.range("bloom.radius", 0.25f, 4.0f, 1.0f))
                // No integer/count Option.Kind exists yet; modeled as a float and rounded where consumed.
                .option(Option.range("bloom.levels", 1.0f, 8.0f, 6.0f))
                .option(Option.range("sky.sun-noon-south-tilt-degrees", -89.0f, 89.0f, 30.0f))
                .option(Option.range("sky.sun-angular-radius-degrees", 0.0f, 20.0f, 0.6f))
                .option(Option.range("sky.moon-angular-radius-degrees", 0.0f, 20.0f, 1.5f))
                .option(Option.range("sky.sun-disc-half-angle-degrees", 0.0f, 45.0f, 16.7f))
                .option(Option.range("sky.moon-disc-half-angle-degrees", 0.0f, 45.0f, 11.31f))
                .option(Option.range("sky.ground-albedo", 0.0f, 1.0f, 0.1f))
                .option(Option.range("sky.horizon-soften-degrees", 0.0f, 90.0f, 15.0f))
                .renderPass(new BloomPass())
                .renderPass(skyLut)
                .renderPass(new WorldOverlayPass())
                .sceneProvider(new MinecraftSceneProvider())
                .lightProvider(new MinecraftLightProvider())
                .lightProvider(skyLut)
                .materialSource(new MinecraftMaterialSource())
                .register();
        registry.setDefault(Slots.SKY, ID);
        registry.setDefault(Slots.SURFACE, ID);
        registry.setDefault(Slots.MEDIUM, ID);
    }
}
