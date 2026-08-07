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
                .shaderSource(ShaderSource.classpath("/caustica/shaders/builtin", "sky", "surface", "medium", "bloom", "common"))
                .bind(Slots.SKY, "caustica_sky_slot", "LutSky")
                .bind(Slots.SURFACE, "caustica_builtin_surface", "BuiltinSurface")
                .bind(Slots.MEDIUM, "caustica_builtin_medium", "BuiltinMedium")
                // Anchors SkyLutPass's own binding declarations outside the generic Sky-slot mechanism —
                // Slang forbids a slot IMPLEMENTATION from declaring global shader parameters itself. See
                // FeatureBuilder.passResourceModule's javadoc.
                .passResourceModule("caustica_sky_bindings")
                // Registered from the Option constants each pass declares rather than restated here: a
                // reader passes the same constant to PassOptions#get, so an id, range or default exists
                // exactly once and a typo cannot compile.
                .options(BloomPass.OPTIONS)
                .options(SkyLutPass.OPTIONS)
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
