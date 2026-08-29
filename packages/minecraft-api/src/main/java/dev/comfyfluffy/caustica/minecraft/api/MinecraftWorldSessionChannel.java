package dev.comfyfluffy.caustica.minecraft.api;

/** Process-time registration of Minecraft client-world contribution factories. */
public interface MinecraftWorldSessionChannel {
    MinecraftWorldSessionRegistration add(MinecraftWorldSessionFactory factory);
}
