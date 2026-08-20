package dev.comfyfluffy.caustica.minecraft.api;

/**
 * Resolves a Minecraft material or returns {@code null} to continue to the next resolver/default. One
 * resolver runs across the finite profile/topology cross-product, so shared textures should be registered
 * once per registrar identity rather than once per request.
 */
@FunctionalInterface
public interface MinecraftMaterialResolver {
    MinecraftMaterialResolution resolve(MinecraftMaterialRequest request);
}
