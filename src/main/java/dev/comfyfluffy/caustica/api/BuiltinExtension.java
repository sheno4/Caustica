package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.pass.BloomPass;
import dev.comfyfluffy.caustica.rt.pass.SkyLutPass;
import dev.comfyfluffy.caustica.rt.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.rt.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.rt.provider.MinecraftSceneProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class BuiltinExtension implements CausticaExtension {
    static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "builtin");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(ID)
                .title(Component.translatable("feature.caustica.builtin"))
                .category(FeatureCategory.GENERAL)
                .shaderSource(ShaderSource.classpath("/caustica/shaders/builtin"))
                .bind(Slots.SKY, "caustica_builtin_sky", "BuiltinSky")
                .bind(Slots.SURFACE, "caustica_builtin_surface", "BuiltinSurface")
                .bind(Slots.MEDIUM, "caustica_builtin_medium", "BuiltinMedium")
                .renderPass(new BloomPass(RtLookPackage.current().bloom()))
                .renderPass(new SkyLutPass())
                .sceneProvider(new MinecraftSceneProvider())
                .lightProvider(new MinecraftLightProvider())
                .materialSource(new MinecraftMaterialSource())
                .register();
        registry.setDefault(Slots.SKY, ID);
        registry.setDefault(Slots.SURFACE, ID);
        registry.setDefault(Slots.MEDIUM, ID);
    }
}
