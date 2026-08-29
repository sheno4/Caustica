package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.gpu.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramContributionChannel;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.scene.GeometryContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.LightContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import dev.comfyfluffy.caustica.engine.scene.SceneEnvironmentContributionChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SceneDirectoryTest {
    interface Implementation { }
    interface Binding { }
    interface Instance { }
    interface EnvironmentBindingData { }
    private static final ShaderDataType<Implementation> IMPLEMENTATION = ShaderDataType.create("impl");
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final ShaderDataType<Instance> INSTANCE = ShaderDataType.create("instance");
    private static final ShaderDataType<EnvironmentBindingData> ENVIRONMENT_BINDING =
            ShaderDataType.create("environment binding");

    @Test
    void environmentSelectionValidatesSessionAndSchemaAndRetiresAfterPublication() {
        ProgramFixture programs = new ProgramFixture();
        ProgramRegistration<EnvironmentId<EnvironmentBindingData>> registration =
                programs.environment(new ContributionOwner(1));
        EnvironmentId<EnvironmentBindingData> environment = registration.exports();
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        SceneEnvironmentContributionChannel channel =
                directory.openEnvironment(new ContributionOwner(2), scene);
        AtomicInteger firstRetired = new AtomicInteger();

        channel.select(new EnvironmentBinding<>(environment, ENVIRONMENT_BINDING.data(7),
                firstRetired::incrementAndGet));
        assertEquals(environment, directory.snapshot().scenes().getFirst().environment().implementation());
        assertThrows(IllegalArgumentException.class, () -> channel.select(mismatchedBinding(environment)));
        EnvironmentId<EnvironmentBindingData> foreign =
                new ProgramFixture().environment(new ContributionOwner(3)).exports();
        assertThrows(IllegalArgumentException.class, () -> channel.select(
                EnvironmentBinding.of(foreign, ENVIRONMENT_BINDING.data(0))));

        registration.close();
        programs.session.progress();
        programs.session.progress();
        channel.select(EnvironmentBinding.of(environment, ENVIRONMENT_BINDING.data(9)));
        backend.retireLatest();
        directory.progressCallbacks();
        assertEquals(1, firstRetired.get());

        channel.invalidate();
        backend.retireLatest();
        directory.progressCallbacks();
        channel.drain();
        assertEquals(null, directory.snapshot().scenes().getFirst().environment());
    }

    @Test
    void crossContributionProgramReferencesWorkButMutationCapabilitiesDoNot() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel first = directory.openGeometry(new ContributionOwner(2));
        GeometryContributionChannel second = directory.openGeometry(new ContributionOwner(3));
        MeshId<Instance> mesh = first.newMesh(INSTANCE);
        var instance = first.newInstance();

        first.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(instance, scene, mesh, GeometryTransform.translation(1, 2, 3),
                        0xff, INSTANCE.data(9)))));
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(1, directory.snapshot().instances().size());

        assertThrows(IllegalArgumentException.class, () -> second.submit(RetainedBatch.of(List.of(
                new GeometryChannel.DropMesh<>(mesh)))));
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(1, directory.snapshot().instances().size());
    }

    @Test
    void primitiveLightMapsAreSessionScopedPlacementSelectionsBoundedByTheMesh() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        LightContributionChannel firstLights = directory.openLights(new ContributionOwner(3));
        LightContributionChannel secondLights = directory.openLights(new ContributionOwner(4));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var firstInstance = geometry.newInstance();
        var secondInstance = geometry.newInstance();
        var firstLight = firstLights.newLight();
        var secondLight = secondLights.newLight();

        geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(firstInstance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0),
                        new PrimitiveLightMap(List.of(new PrimitiveLightMap.Range(0, 1, firstLight)))),
                new GeometryChannel.SetInstance<>(secondInstance, scene, mesh,
                        GeometryTransform.translation(1, 0, 0), 0xff, INSTANCE.data(0),
                        new PrimitiveLightMap(List.of(new PrimitiveLightMap.Range(0, 1, secondLight)))))));

        List<RetainedSceneSnapshot.Instance> instances = directory.snapshot().instances();
        assertEquals(2, instances.size());
        assertEquals(1, instances.get(0).primitiveEmitters().size());
        assertEquals(1, instances.get(1).primitiveEmitters().size());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                instances.get(0).primitiveEmitters().getFirst().lightIdentity(),
                instances.get(1).primitiveEmitters().getFirst().lightIdentity());

        assertThrows(IllegalArgumentException.class, () -> geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetInstance<>(firstInstance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0),
                        new PrimitiveLightMap(List.of(new PrimitiveLightMap.Range(1, 1, firstLight))))))));

        SceneDirectory foreignDirectory = directory(new ProgramFixture(), new SceneBackend());
        LightContributionChannel foreignLights = foreignDirectory.openLights(new ContributionOwner(5));
        var foreignLight = foreignLights.newLight();
        assertThrows(IllegalArgumentException.class, () -> geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetInstance<>(firstInstance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0),
                        new PrimitiveLightMap(List.of(new PrimitiveLightMap.Range(0, 1, foreignLight))))))));
    }

    @Test
    void invalidBatchChangesNothingAndDoesNotTakeRetirementCallback() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        SceneId foreignScene = directory(programs, new SceneBackend()).createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        AtomicInteger retired = new AtomicInteger();
        int publications = backend.snapshots.size();

        assertThrows(IllegalArgumentException.class, () -> geometry.submit(new RetainedBatch<>(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(instance, foreignScene, mesh, GeometryTransform.translation(0, 0, 0),
                        0xff, INSTANCE.data(0))), retired::incrementAndGet)));

        assertEquals(publications, backend.snapshots.size());
        assertEquals(0, directory.snapshot().meshes().size());
        assertEquals(0, retired.get());
    }

    @Test
    void synchronousBackendRejectionRollsBackAndDoesNotTakeBatchRetirement() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        AtomicInteger retired = new AtomicInteger();
        backend.rejectNext = true;

        assertThrows(IllegalStateException.class, () -> geometry.submit(new RetainedBatch<>(
                List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface))), retired::incrementAndGet)));

        assertEquals(0, directory.snapshot().meshes().size());
        directory.progressCallbacks();
        assertEquals(0, retired.get());
    }

    @Test
    void meshAndSceneCascadesRetireWholeBatchesOnlyAfterBackendAcknowledgment() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        LightContributionChannel lights = directory.openLights(new ContributionOwner(3));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        var light = lights.newLight();
        AtomicInteger geometryRetired = new AtomicInteger();
        AtomicInteger lightRetired = new AtomicInteger();
        geometry.submit(new RetainedBatch<>(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(instance, scene, mesh, GeometryTransform.translation(0, 0, 0),
                        0xff, INSTANCE.data(0))), geometryRetired::incrementAndGet));
        lights.submit(new RetainedBatch<>(List.of(new LightChannel.SetLight(light, scene,
                new LightDescriptor.Spot(0, 1, 0, 0, -1, 0, 10, 0.5, 1, 1, 1))),
                lightRetired::incrementAndGet));

        directory.dropScene(scene);
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(0, directory.snapshot().instances().size());
        assertEquals(0, directory.snapshot().lights().size());
        backend.retireLatest();
        directory.progressCallbacks();
        assertEquals(0, geometryRetired.get(), "mesh from the same batch is still retained");
        assertEquals(1, lightRetired.get());

        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))));
        backend.retireLatest();
        directory.progressCallbacks();
        assertEquals(1, geometryRetired.get());
    }

    private static MeshBuild<Instance> mesh(SurfaceId<Binding, Instance> surface) {
        MeshBuild.Stream positions = new MeshBuild.Stream(
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x1000), 36), 12);
        MeshBuild.Stream indices = new MeshBuild.Stream(
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x2000), 12), 4);
        var slot = new MeshBuild.SurfaceSlot<>(surface, BINDING.data(0), new MeshBuild.CoveragePolicy.Opaque());
        return new MeshBuild<>(positions, null, indices, 3, new MeshBuild.IndexRevision(1),
                List.of(new MeshBuild.Geometry<>(slot, null, 0, 3)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EnvironmentBinding<?> mismatchedBinding(EnvironmentId<?> environment) {
        return new EnvironmentBinding((EnvironmentId) environment,
                ShaderDataType.create("wrong").data(0), () -> { });
    }

    private static SceneDirectory directory(ProgramFixture programs, SceneBackend backend) {
        return new SceneDirectory(programs.session, backend, failure -> { throw new AssertionError(failure); });
    }

    private static final class ProgramFixture {
        private final ImmediateProgramBackend backend = new ImmediateProgramBackend();
        private final ProgramSession session = new ProgramSession(backend, failure -> { throw new AssertionError(failure); });
        SurfaceId<Binding, Instance> surface(ContributionOwner owner) {
            ProgramContributionChannel channel = session.openChannel(owner);
            var registration = channel.register(builder -> builder.surface(new SurfaceDefinition<>(
                    new ShaderDefinition(ShaderSource.classpath(SceneDirectoryTest.class, "/shaders"),
                            "surface", "test::Surface"), null, IMPLEMENTATION.data(0), BINDING, INSTANCE, () -> { })));
            session.progress();
            session.progress();
            return registration.exports();
        }
        ProgramRegistration<EnvironmentId<EnvironmentBindingData>> environment(ContributionOwner owner) {
            ProgramContributionChannel channel = session.openChannel(owner);
            ProgramRegistration<EnvironmentId<EnvironmentBindingData>> registration = channel.register(
                    builder -> builder.environment(new EnvironmentDefinition<>(new ShaderDefinition(
                            ShaderSource.classpath(SceneDirectoryTest.class, "/shaders"),
                            "environment", "test::Environment"), ENVIRONMENT_BINDING)));
            session.progress();
            session.progress();
            return registration;
        }
    }

    private static final class ImmediateProgramBackend implements ProgramBackend {
        @Override public void compile(ProgramComposition composition,
                                      java.util.function.Consumer<? super Compilation> completion) {
            completion.accept(new Compilation.Succeeded(key -> (int) key.sequence()));
        }
        @Override public void publish(CompiledProgram program, Runnable previousRetired) { previousRetired.run(); }
        @Override public void drainPublishedUses() { }
        @Override public void discard(CompiledProgram program) { }
    }

    private static final class SceneBackend implements RetainedSceneBackend {
        private final List<RetainedSceneSnapshot> snapshots = new ArrayList<>();
        private final List<Runnable> retirements = new ArrayList<>();
        private boolean rejectNext;
        @Override public void publish(RetainedSceneSnapshot snapshot, Runnable previousRetired) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected native publication");
            }
            snapshots.add(snapshot); retirements.add(previousRetired);
        }
        void retireLatest() { retirements.getLast().run(); }
    }
}
