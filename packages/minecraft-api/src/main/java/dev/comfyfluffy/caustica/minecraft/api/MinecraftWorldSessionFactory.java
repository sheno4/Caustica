package dev.comfyfluffy.caustica.minecraft.api;

/** Creates one contribution for each client-world/dimension epoch in a live renderer session. */
@FunctionalInterface
public interface MinecraftWorldSessionFactory {
    MinecraftWorldSessionContribution open(MinecraftWorldSessionContext context);
}
