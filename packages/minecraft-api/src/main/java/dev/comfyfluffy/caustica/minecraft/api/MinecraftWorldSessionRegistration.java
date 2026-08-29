package dev.comfyfluffy.caustica.minecraft.api;

/** Process-lived registration of one Minecraft world-session factory. */
public interface MinecraftWorldSessionRegistration extends AutoCloseable {
    /** Stop using this factory for future world epochs and request removal from live epochs. */
    @Override
    void close();
}
