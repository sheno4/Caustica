/**
 * Loader-neutral Minecraft world-session capabilities layered on the renderer-generic API.
 * Minecraft-only integrations implement {@link dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension};
 * loaders discover that capability independently from renderer-generic extensions. Minecraft-owned shader data
 * identities are published from {@link dev.comfyfluffy.caustica.minecraft.api.program}.
 */
package dev.comfyfluffy.caustica.minecraft.api;
