package dev.comfyfluffy.caustica.engine.program;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable declarations and implementation identities for one compiled renderer program. */
public final class ProgramComposition {
    private final List<Declaration> declarations;
    private final IdentityHashMap<Object, Integer> implementations;

    public ProgramComposition(List<Declaration> declarations) {
        this(declarations, Map.of());
    }

    public ProgramComposition(List<Declaration> declarations, Map<Object, Integer> implementations) {
        this.declarations = List.copyOf(declarations);
        this.implementations = new IdentityHashMap<>(implementations);
    }

    public List<Declaration> declarations() { return declarations; }

    /** Resolves this composition's surface, or zero for an absent/foreign surface. */
    public int resolve(SurfaceId<?, ?> id) { return implementations.getOrDefault(id, 0); }

    /** Resolves this composition's volume, or zero for vacuum. */
    public int resolve(VolumeId<?, ?> id) { return implementations.getOrDefault(id, 0); }

    /** Resolves this composition's environment, or zero for the error environment. */
    public int resolve(EnvironmentId<?> id) { return implementations.getOrDefault(id, 0); }

    public sealed interface Declaration permits Surface, Volume, Environment {
        ProgramKey key();
    }

    public record Surface(ProgramKey key, SurfaceDefinition<?, ?> definition) implements Declaration {
        public Surface {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(definition, "definition");
        }
    }

    public record Volume(ProgramKey key, VolumeDefinition<?, ?> definition) implements Declaration {
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
