package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtProgramResolutionTest {
    private static final ShaderDataType<Void> DATA = ShaderDataType.create("test data");

    @Test
    void retainedProgramsResolveTheirOwnDeclarationsAfterSessionPublicationChanges() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = session(backend);
        var channel = session.openChannel();
        var first = channel.register(builder -> declare(builder, "First"));
        session.progress();
        backend.succeed();
        session.progress();
        RtProgramBackend.Published original = backend.active;
        assertResolved(original, first.exports(), 1);

        var second = channel.register(builder -> declare(builder, "Second"));
        session.progress();
        assertResolved(original, second.exports(), 0);
        backend.succeed();
        session.progress();
        RtProgramBackend.Published expanded = backend.active;
        assertResolved(original, first.exports(), 1);
        assertResolved(original, second.exports(), 0);
        assertResolved(expanded, first.exports(), 1);
        assertResolved(expanded, second.exports(), 2);

        first.close();
        session.progress();
        assertResolved(expanded, first.exports(), 1);
        backend.succeed();
        session.progress();
        assertEquals(0, session.resolve(first.exports().surface()));
        assertEquals(0, session.resolve(first.exports().volume()));
        assertEquals(0, session.resolve(first.exports().environment()));
        assertResolved(backend.active, first.exports(), 0);
        assertResolved(backend.active, second.exports(), 2);
        assertResolved(original, first.exports(), 1);
        assertResolved(expanded, first.exports(), 1);
        assertResolved(expanded, second.exports(), 2);
    }

    @Test
    void publishedResolutionUsesFallbacksForFailedForeignAndAbsentIds() {
        ManualBackend backend = new ManualBackend();
        ProgramSession session = session(backend);
        var channel = session.openChannel();
        var first = channel.register(builder -> declare(builder, "First"));
        session.progress();
        backend.succeed();
        session.progress();

        var failed = channel.register(builder -> declare(builder, "Failed"));
        session.progress();
        backend.fail();
        session.progress();
        assertResolved(backend.active, failed.exports(), 0);

        ManualBackend foreignBackend = new ManualBackend();
        ProgramSession foreignSession = session(foreignBackend);
        var foreign = foreignSession.openChannel()
                .register(builder -> declare(builder, "Foreign"));
        foreignSession.progress();
        foreignBackend.succeed();
        foreignSession.progress();
        assertResolved(foreignBackend.active, foreign.exports(), 1);
        assertResolved(backend.active, foreign.exports(), 0);
        assertResolved(foreignBackend.active, first.exports(), 0);
        assertResolved(backend.active, new Exports(null, null, null), 0);
        assertResolved(backend.active, new Exports(
                new SurfaceId<>() { }, new VolumeId<>() { }, new EnvironmentId<>() { }), 0);
    }

    private static ProgramSession session(ManualBackend backend) {
        return new ProgramSession(new ResourceDirectory(failure -> { throw new AssertionError(failure); }),
                backend, failure -> { throw new AssertionError(failure); });
    }

    private static Exports declare(ProgramBuilder builder, String name) {
        ShaderSource source = ShaderSource.classpath(RtProgramResolutionTest.class, "/shaders");
        return new Exports(
                builder.surface(SurfaceDefinition.opaque(
                        new ShaderDefinition(source, "test", "test." + name + "Surface"),
                        DATA.data(0), DATA, DATA)),
                builder.volume(VolumeDefinition.of(
                        new ShaderDefinition(source, "test", "test." + name + "Volume"),
                        DATA.data(0), DATA, DATA)),
                builder.environment(new EnvironmentDefinition<>(
                        new ShaderDefinition(source, "test", "test." + name + "Environment"), DATA)));
    }

    private static void assertResolved(RtProgramBackend.Published program, Exports ids, int expected) {
        assertEquals(expected, program.resolve(ids.surface()));
        assertEquals(expected, program.resolve(ids.volume()));
        assertEquals(expected, program.resolve(ids.environment()));
    }

    private record Exports(SurfaceId<Void, Void> surface, VolumeId<Void, Void> volume,
                           EnvironmentId<Void> environment) { }

    private static final class ManualBackend implements ProgramBackend {
        private ProgramComposition pending;
        private Consumer<? super Compilation> completion;
        private RtProgramBackend.Published active;

        @Override public void compile(ProgramComposition composition, Consumer<? super Compilation> completion) {
            pending = composition;
            this.completion = completion;
        }

        @Override public void publish(CompiledProgram program, Runnable previousRetired) {
            active = (RtProgramBackend.Published) program;
            previousRetired.run();
        }

        @Override public void drainPublishedUses() { }

        void succeed() { completion.accept(new Compilation.Succeeded(new TestProgram(pending))); }

        void fail() { completion.accept(new Compilation.Failed(new ProgramFailure("failed", "test"))); }
    }

    private record TestProgram(ProgramComposition composition) implements RtProgramBackend.Published {
        @Override public RtPipeline pipeline() { return null; }
        @Override public VulkanDeviceAddress compositionDataAddress() { return new VulkanDeviceAddress(0); }
        @Override public void close() { }
    }
}
