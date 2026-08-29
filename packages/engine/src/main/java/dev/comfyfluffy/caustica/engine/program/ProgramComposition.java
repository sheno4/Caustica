package dev.comfyfluffy.caustica.engine.program;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;

import java.util.List;
import java.util.Objects;

/** Immutable ordered input to the renderer's shader composer. */
public record ProgramComposition(long revision, List<RegistrationSet> registrations) {
    public ProgramComposition {
        registrations = List.copyOf(registrations);
    }

    public record RegistrationSet(long acceptanceSequence, List<Declaration> declarations) {
        public RegistrationSet {
            declarations = List.copyOf(declarations);
        }
    }

    public sealed interface Declaration permits Surface, Volume, Environment {
        ProgramKey key();
    }

    public record Surface(ProgramKey key, SurfaceDefinition<?, ?, ?> definition) implements Declaration {
        public Surface {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(definition, "definition");
        }
    }

    public record Volume(ProgramKey key, VolumeDefinition<?, ?, ?> definition) implements Declaration {
        public Volume {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(definition, "definition");
        }
    }

    public record Environment(ProgramKey key, EnvironmentDefinition<?> definition) implements Declaration {
        public Environment {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(definition, "definition");
        }
    }
}
