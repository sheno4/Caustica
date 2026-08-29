package dev.comfyfluffy.caustica.minecraft.api;

/** Lifetime callbacks for one Minecraft client-world/dimension epoch. */
public interface MinecraftWorldSessionContribution extends AutoCloseable {
    MinecraftWorldSessionContribution EMPTY = new MinecraftWorldSessionContribution() { };

    /** Called in generation order after a newer resource pack has become visible to the client. */
    default void resourcePackChanged(ResourcePackEpoch epoch) { }

    /** Stop producers before the borrowed core contribution and scene begin teardown. */
    default void stop() { }

    /** Release CPU state after stop. Borrowed renderer objects remain owned by their core scope. */
    @Override
    default void close() { }
}
