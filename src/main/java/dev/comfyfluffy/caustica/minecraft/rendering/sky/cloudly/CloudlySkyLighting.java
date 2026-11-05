package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;

/** Current-frame atmosphere descriptors borrowed from the retained sky binding. */
public record CloudlySkyLighting(long frameIndex, int ambientIndex, int transmittanceIndex,
                                 int samplerIndex, ResourceOwner owner) { }
