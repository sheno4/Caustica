package dev.comfyfluffy.caustica.minecraft.client.session;

import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.Objects;

/** One isolated Minecraft world-contribution lifecycle failure. */
public record MinecraftSessionFailure(ContributionOwner owner, Stage stage, Throwable cause) {
    public MinecraftSessionFailure {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(cause, "cause");
    }

    public enum Stage {
        CREATE_SCOPE,
        OPEN_CONTRIBUTION,
        RESOURCE_PACK_CHANGED,
        QUIESCE,
        STOP_CONTRIBUTION,
        INVALIDATE,
        DRAIN,
        CLOSE_CONTRIBUTION,
        CLOSE_SCOPE
    }
}
