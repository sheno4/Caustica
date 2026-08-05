package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.pass.BuiltinBloomPass;
import dev.comfyfluffy.caustica.rt.pass.BuiltinSkyLutPass;
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
                .renderPass(new BuiltinBloomPass(RtLookPackage.current().bloom()))
                .renderPass(new BuiltinSkyLutPass())
                .register();
        registry.setDefault(Slots.SKY, ID);
        registry.setDefault(Slots.SURFACE, ID);
        registry.setDefault(Slots.MEDIUM, ID);
    }
}
