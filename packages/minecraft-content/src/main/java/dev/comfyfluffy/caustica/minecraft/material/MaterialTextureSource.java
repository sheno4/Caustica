package dev.comfyfluffy.caustica.minecraft.material;

import java.io.IOException;

/** Opens all images behind one canonical texture as one lifetime, without intermediate image copies. */
@FunctionalInterface
public interface MaterialTextureSource {
    MaterialTextureImage open() throws IOException;
}
