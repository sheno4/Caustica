package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ProgramSceneFallbackTest {
    interface Implementation { }
    interface Binding { }
    interface Instance { }

    private static final ShaderDataType<Implementation> IMPLEMENTATION =
            ShaderDataType.create("implementation");
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final ShaderDataType<Instance> INSTANCE = ShaderDataType.create("instance");
    private record Exports(SurfaceId<Binding, Instance> surface,
                           VolumeId<Binding, Instance> volume) { }

    @Test
    void removingProgramsLeavesRetainedMeshPublicationOnStableFallbackSlots() {
        ImmediateProgramBackend programsBackend = new ImmediateProgramBackend();
        ResourceDirectory resources = new ResourceDirectory(
                failure -> { throw new AssertionError(failure); });
        ProgramSession programs = new ProgramSession(
                resources, programsBackend, failure -> { throw new AssertionError(failure); });
        var programChannel = programs.openChannel(new ContributionOwner(1));
        ProgramRegistration<Exports> registration = programChannel.register(builder -> new Exports(
                builder.surface(new SurfaceDefinition<>(shader("surface", "test.Surface"),
                        shader("coverage", "test.Coverage"), IMPLEMENTATION.data(11),
                        BINDING, INSTANCE)),
                builder.volume(new VolumeDefinition<>(shader("volume", "test.Volume"),
                        IMPLEMENTATION.data(12), BINDING, INSTANCE))));
        progress(programs);

        CapturingSceneBackend scenesBackend = new CapturingSceneBackend();
        SceneDirectory scenes = new SceneDirectory(
                programs, resources,
                scenesBackend, (input, source) -> java.util.concurrent.CompletableFuture.completedFuture(ResourceRef.none().retain()));
        var scene = scenes.createScene();
        var geometry = scenes.openChannel(new ContributionOwner(2));
        var mesh = geometry.prepare(INSTANCE,mesh(registration.exports())).join();
        geometry.edit(List.of(new SceneEdit.SetInstance<>(geometry.newInstance(),scene,mesh,GeometryTransform.translation(0,0,0),255,INSTANCE.data(0))));

        assertEquals(2, scenesBackend.snapshots.size());
        RetainedSceneSnapshot published = scenesBackend.snapshots.getLast();
        RetainedSceneSnapshot.Mesh retainedMesh = published.meshes().getFirst();
        assertEquals(new RetainedSceneSnapshot.GeometryPrograms(1, 1),
                retainedMesh.geometryPrograms().getFirst());

        registration.close();
        progress(programs);

        assertEquals(0, programs.resolve(registration.exports().surface()));
        assertEquals(0, programs.resolve(registration.exports().volume()));
        assertEquals(List.of(), programsBackend.activeComposition.declarations());
        assertEquals(2, scenesBackend.snapshots.size(),
                "program removal must not require a retained-scene republish");
        assertSame(published, scenesBackend.snapshots.getLast());
        assertEquals(retainedMesh.identity(), scenes.snapshot().meshes().getFirst().identity());
        assertEquals(new RetainedSceneSnapshot.GeometryPrograms(1, 1),
                scenesBackend.snapshots.getLast().meshes().getFirst().geometryPrograms().getFirst());
    }

    @Test
    void preparedMeshStartsUsingProgramAfterCompilationWithoutAnotherSceneEdit() {
        var resources=new ResourceDirectory(failure->{throw new AssertionError(failure);});
        var programs=new ProgramSession(resources,new ImmediateProgramBackend(),failure->{throw new AssertionError(failure);});
        var registration=programs.openChannel(new ContributionOwner(1)).register(builder->new Exports(
            builder.surface(new SurfaceDefinition<>(shader("surface","test.Surface"),shader("coverage","test.Coverage"),
                IMPLEMENTATION.data(0),BINDING,INSTANCE)),
            builder.volume(new VolumeDefinition<>(shader("volume","test.Volume"),IMPLEMENTATION.data(0),BINDING,INSTANCE))));
        var backend=new CapturingSceneBackend();
        var scenes=new SceneDirectory(programs,resources,backend,
            (input,source)->java.util.concurrent.CompletableFuture.completedFuture(ResourceRef.none().retain()));
        var scene=scenes.createScene();var channel=scenes.openChannel(new ContributionOwner(2));
        var ready=channel.prepare(INSTANCE,mesh(registration.exports())).join();
        channel.edit(List.of(new SceneEdit.SetInstance<>(channel.newInstance(),scene,ready,
            GeometryTransform.translation(0,0,0),255,INSTANCE.data(0))));
        assertEquals(new RetainedSceneSnapshot.GeometryPrograms(0,0),backend.snapshots.getLast().meshes().getFirst().geometryPrograms().getFirst());
        progress(programs);scenes.progress();
        assertEquals(new RetainedSceneSnapshot.GeometryPrograms(1,1),backend.snapshots.getLast().meshes().getFirst().geometryPrograms().getFirst());
        registration.close();progress(programs);scenes.progress();
        assertEquals(new RetainedSceneSnapshot.GeometryPrograms(0,0),backend.snapshots.getLast().meshes().getFirst().geometryPrograms().getFirst());
    }

    private static void progress(ProgramSession programs) {
        programs.progress();
        programs.progress();
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(
                ShaderSource.classpath(ProgramSceneFallbackTest.class, "/shaders"), module, type);
    }

    private static MeshBuild<Instance> mesh(Exports exports) {
        return new MeshBuild<>(
                new MeshBuild.Stream(new VulkanDeviceAddressRange(
                        new VulkanDeviceAddress(0x1000), 3L * 3L * Float.BYTES), 3 * Float.BYTES,
                        ResourceRef.none()),
                new MeshBuild.Stream(new VulkanDeviceAddressRange(
                        new VulkanDeviceAddress(0x2000), 3L * Integer.BYTES), Integer.BYTES,
                        ResourceRef.none()),
                3, new MeshBuild.IndexRevision(1), List.of(new MeshBuild.Geometry<>(
                        new MeshBuild.SurfaceSlot<>(exports.surface(), BINDING.data(21),
                                new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                        new MeshBuild.VolumeSlot<>(exports.volume(), BINDING.data(22)), 0, 3)));
    }

    private static final class ImmediateProgramBackend implements ProgramBackend {
        private ProgramComposition activeComposition;

        @Override
        public void compile(ProgramComposition composition,
                            java.util.function.Consumer<? super Compilation> completion) {
            pendingComposition = composition;
            completion.accept(new Compilation.Succeeded(new CompiledProgram() {
                @Override public void close() { }
            }));
        }

        private ProgramComposition pendingComposition;

        @Override
        public void publish(CompiledProgram program, Runnable previousRetired) {
            activeComposition = pendingComposition;
            previousRetired.run();
        }

        @Override public void drainPublishedUses() { }
    }

    private static final class CapturingSceneBackend implements RetainedSceneBackend {
        private final List<RetainedSceneSnapshot> snapshots = new ArrayList<>();

        @Override
        public void apply(RetainedSceneSnapshot snapshot) {
            snapshots.add(snapshot);
        }

    }
}
