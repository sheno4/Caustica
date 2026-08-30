package dev.comfyfluffy.caustica.engine.program;

import dev.comfyfluffy.caustica.api.program.ProgramFailure;

import java.util.Objects;
import java.util.function.Consumer;

/** Minecraft-independent bridge to asynchronous shader composition and renderer publication. */
public interface ProgramBackend {
    /** Starts compilation. The completion may arrive from any thread and must be delivered exactly once. */
    void compile(ProgramComposition composition, Consumer<? super Compilation> completion);

    /**
     * Takes ownership of {@code program} and makes it active at a renderer publication boundary. The backend invokes
     * {@code previousRetired} after the displaced program has no active or in-flight GPU use.
     */
    void publish(CompiledProgram program, Runnable previousRetired);

    /**
     * Waits for renderer use of displaced published programs to finish and delivers their retirement
     * callbacks before returning. Session owner drainage invokes this after advancing publication so it
     * cannot block while the renderer is the only component able to advance GPU retirement.
     */
    void drainPublishedUses();

    /** Caller-owned successful compilation. Publication transfers ownership to the backend. */
    interface CompiledProgram extends AutoCloseable {
        /** Renderer-assigned positive dispatch index for a declaration in this exact composition; zero is reserved. */
        int implementationIndex(ProgramKey key);

        /** Releases a candidate which was not published. */
        @Override void close();
    }

    sealed interface Compilation permits Compilation.Succeeded, Compilation.Failed {
        record Succeeded(CompiledProgram program) implements Compilation {
            public Succeeded { Objects.requireNonNull(program, "program"); }
        }

        record Failed(ProgramFailure failure) implements Compilation {
            public Failed { Objects.requireNonNull(failure, "failure"); }
        }
    }
}
