package dev.comfyfluffy.caustica.platform;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import java.nio.file.Path;
import java.util.List;

/** Loader services consumed by the shared Caustica runtime. */
public interface CausticaPlatform {
    Path gameDir();

    Path configDir();

    List<CausticaExtension> extensions();

    static CausticaPlatform current() {
        return Holder.current;
    }

    static void install(CausticaPlatform platform) {
        Holder.current = platform;
    }

    final class Holder {
        private static CausticaPlatform current = new StandalonePlatform();

        private Holder() {
        }
    }

    final class StandalonePlatform implements CausticaPlatform {
        @Override
        public Path gameDir() {
            return Path.of(".");
        }

        @Override
        public Path configDir() {
            return Path.of("config");
        }

        @Override
        public List<CausticaExtension> extensions() {
            return List.of();
        }
    }
}
