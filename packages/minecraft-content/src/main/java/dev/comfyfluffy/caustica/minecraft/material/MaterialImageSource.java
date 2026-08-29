package dev.comfyfluffy.caustica.minecraft.material;

import java.io.IOException;

/** Opens one borrowed or owned image view for a material compile operation. */
@FunctionalInterface
interface MaterialImageSource {
    MaterialImage open() throws IOException;
}
