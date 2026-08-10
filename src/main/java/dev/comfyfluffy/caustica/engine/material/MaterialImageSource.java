package dev.comfyfluffy.caustica.engine.material;

import java.io.IOException;

/** Opens one borrowed or owned image view for a material compile operation. */
@FunctionalInterface
public interface MaterialImageSource {
    MaterialImage open() throws IOException;
}
