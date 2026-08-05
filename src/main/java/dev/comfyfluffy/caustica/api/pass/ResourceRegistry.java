package dev.comfyfluffy.caustica.api.pass;

import net.minecraft.resources.Identifier;

public interface ResourceRegistry {
    ImageRef engineImage(EngineImage image);

    ImageRef image(Identifier id, ImageFormat format, ImageSize size);

    ImagePyramid imagePyramid(Identifier id, ImageFormat format, ImageSize baseSize,
                              int maxLevels, int minimumDimension);

    void publish(EngineImage slot, ImageRef image);

    ComputeProgram compute(ComputeProgram program);
}
