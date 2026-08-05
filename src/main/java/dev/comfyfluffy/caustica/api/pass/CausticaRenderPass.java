package dev.comfyfluffy.caustica.api.pass;

import net.minecraft.resources.Identifier;

public interface CausticaRenderPass {
    Identifier id();

    RenderStage stage();

    void declareResources(ResourceRegistry resources);

    default void initialize(PassContext context) {
    }

    void record(PassContext context);
}
