package dev.comfyfluffy.caustica.api.pass;

import net.minecraft.resources.Identifier;

public interface CausticaRenderPass {
    Identifier id();

    RenderStage stage();

    void declareResources(ResourceRegistry resources);

    void record(PassContext context);
}
