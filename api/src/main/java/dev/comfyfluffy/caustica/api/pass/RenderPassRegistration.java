package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.ContextualRuntimeFactory;
import dev.comfyfluffy.caustica.api.ResourceId;
import java.util.Objects;

/** A runtime-activation factory and its process-stable identity. */
public record RenderPassRegistration(ResourceId id, RenderStage stage,
                                     ContextualRuntimeFactory<? extends CausticaRenderPass> factory) {
    public RenderPassRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(factory, "factory");
    }
}
