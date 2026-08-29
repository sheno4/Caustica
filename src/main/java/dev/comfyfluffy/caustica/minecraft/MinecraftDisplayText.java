package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.DisplayText;
import net.minecraft.network.chat.Component;

/** Converts API display text at the Minecraft UI boundary. */
public final class MinecraftDisplayText {
    private MinecraftDisplayText() {
    }

    public static Component component(DisplayText text) {
        return switch (text.kind()) {
            case LITERAL -> Component.literal(text.value());
            case TRANSLATABLE -> Component.translatable(text.value());
        };
    }
}
