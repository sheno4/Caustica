package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;

/** Compile-only consumer of every Vulkan-native API feature category. */
public final class ApiShowcaseExtension implements CausticaExtension {
    @Override
    public void register(CausticaApi api) {
        api.sessions().add(ShowcaseSession::new);
    }
}
