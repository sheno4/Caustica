package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.RuntimeFactory;
import java.util.Objects;

/** A runtime-activation factory and its process-stable identity. */
public record RenderPassRegistration(ResourceId id, RenderStage stage,
                                     RuntimeFactory<? extends CausticaRenderPass> factory) {
    public RenderPassRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(factory, "factory");
    }
}
