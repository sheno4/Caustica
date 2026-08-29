package dev.comfyfluffy.caustica.engine.program;

/** Resolution of a possibly stale public program ID against the currently published composition. */
public final class ProgramResolution {
    private ProgramResolution() { }

    public sealed interface Surface permits ActiveSurface, ErrorSurface { }
    public record ActiveSurface(int implementationIndex) implements Surface { }
    public enum ErrorSurface implements Surface { INSTANCE }

    public sealed interface Volume permits ActiveVolume, Vacuum { }
    public record ActiveVolume(int implementationIndex) implements Volume { }
    public enum Vacuum implements Volume { INSTANCE }

    public sealed interface Environment permits ActiveEnvironment, ErrorEnvironment { }
    public record ActiveEnvironment(int implementationIndex) implements Environment { }
    public enum ErrorEnvironment implements Environment { INSTANCE }
}
