package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
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
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramContributionChannel;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.scene.GeometryContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.LightContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneContentSnapshot;
import dev.comfyfluffy.caustica.engine.scene.RetainedInstanceTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import dev.comfyfluffy.caustica.engine.scene.SceneEnvironmentContributionChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void sceneRetainsEveryDependencyAfterProducerRelease() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        VolumeId<Binding, Instance> volume = programs.volume(new ContributionOwner(1));
        ResourceDirectory resources = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        SceneDirectory directory = directory(programs, resources, new SceneBackend());
        SceneId scene = directory.createScene();
        ContributionOwner owner = new ContributionOwner(2);
        GeometryContributionChannel geometry = directory.openGeometry(owner);
        var factory = resources.openFactory(owner);
        AtomicInteger retired = new AtomicInteger();
        var positions = factory.create(retired::incrementAndGet);
        var indices = factory.create(retired::incrementAndGet);
        var surfaceData = factory.create(retired::incrementAndGet);
        var volumeData = factory.create(retired::incrementAndGet);
        var instanceData = factory.create(retired::incrementAndGet);
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        MeshBuild<Instance> build = mesh(surface, volume, positions.reference(), indices.reference(),
                surfaceData.reference(), volumeData.reference());
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, build),
                new GeometryChannel.SetInstance<>(instance, scene, mesh, GeometryTransform.translation(0,0,0),
                        0xff, INSTANCE.data(17, instanceData.reference())))));
        List.of(positions, indices, surfaceData, volumeData, instanceData).forEach(ResourceOwner::close);
        resources.awaitRetirements();
        assertEquals(0, retired.get());
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))));
        resources.awaitRetirements();
        assertEquals(5, retired.get());
        resources.close();
    }

    @Test
    void sceneCanRetainAnotherContributionsResource() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        ResourceDirectory resources = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        SceneDirectory directory = directory(programs, resources, new SceneBackend());
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        AtomicInteger retired = new AtomicInteger();
        ResourceOwner shared = resources.openFactory(new ContributionOwner(3)).create(retired::incrementAndGet);
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh,
                mesh(surface, null, shared.reference(), ResourceRef.none(), ResourceRef.none(), ResourceRef.none())))));
        shared.close();
        resources.awaitRetirements();
        assertEquals(0, retired.get());
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))));
        resources.awaitRetirements();
        assertEquals(1, retired.get());
        resources.close();
    }

    @Test
    void environmentSelectionOwnsItsOpaqueDataThroughSceneRemoval() {
        ProgramFixture programs = new ProgramFixture();
        EnvironmentId<EnvironmentBindingData> environment = programs.environment(new ContributionOwner(1)).exports();
        ResourceDirectory resources = new ResourceDirectory(failure -> { throw new AssertionError(failure); });
        SceneDirectory directory = directory(programs, resources, new SceneBackend());
        SceneId scene = directory.createScene();
        ContributionOwner owner = new ContributionOwner(2);
        SceneEnvironmentContributionChannel environments = directory.openEnvironment(owner, scene);
        AtomicInteger retired = new AtomicInteger();
        ResourceOwner data = resources.openFactory(owner).create(retired::incrementAndGet);
        environments.select(EnvironmentBinding.of(environment, ENVIRONMENT_BINDING.data(5, data.reference())));
        data.close();
        resources.awaitRetirements();
        assertEquals(0, retired.get());
        assertSame(data.reference(), directory.snapshot().scenes().getFirst().environment().bindingData().resource());
        directory.dropScene(scene);
        resources.awaitRetirements();
        assertEquals(1, retired.get());
        resources.close();
    }

    @Test
    void droppingResourceOwnerDoesNotRepublishOrRemoveLogicalGeometry() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        ResourceDirectory resources = new ResourceDirectory(
                failure -> { throw new AssertionError(failure); });
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, resources, backend);
        SceneId scene = directory.createScene();
        ContributionOwner owner = new ContributionOwner(2);
        GeometryContributionChannel geometry = directory.openGeometry(owner);
        ResourceOwner data = resources.openFactory(owner).create();

        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        MeshBuild<Instance> build = mesh(surface, null, data.reference(), data.reference(),
                data.reference(), ResourceRef.none());
        geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, build),
                new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff,
                        INSTANCE.data(3, data.reference())))));
        int publications = backend.snapshots.size();
        long revision = directory.snapshot().revision();

        data.close();

        assertEquals(publications, backend.snapshots.size());
        assertEquals(revision, directory.snapshot().revision());
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(1, directory.snapshot().instances().size());
        assertSame(data.reference(), directory.snapshot().meshes().getFirst()
                .build().positions().resource());
        assertSame(data.reference(), directory.snapshot().instances().getFirst()
                .instanceData().resource());
    }

    @Test
    void sessionCloseSettlesAcceptedOwnerWorkWithoutAnotherFrame() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        AsyncSceneBackend backend = new AsyncSceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        AtomicBoolean retired = new AtomicBoolean();
        geometry.submit(RetainedBatch.of(
                List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));
        geometry.invalidate();

        directory.prepareForSessionClose();
        geometry.drain();

        assertFalse(retired.get());
    }

    @Test
    void ownerDrainWakesAndProgressesAcceptedBackendPublications() throws InterruptedException {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        AsyncSceneBackend backend = new AsyncSceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));
        geometry.invalidate();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();

        Thread drain = Thread.ofVirtual().name("retained-owner-drain").start(() -> {
            try {
                geometry.drain();
            } catch (Throwable failure) {
                drainFailure.set(failure);
            }
        });
        assertTrue(backend.awaitProgress());
        assertTrue(drain.isAlive());

        backend.completeAll();
        drain.join(2_000L);

        assertFalse(drain.isAlive());
        assertNull(drainFailure.get());
    }

    @Test
    void ownerDrainPropagatesFatalAcceptedPublicationFailureAfterWakeup() throws InterruptedException {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        AsyncSceneBackend backend = new AsyncSceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));
        geometry.invalidate();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        OutOfMemoryError fatal = new OutOfMemoryError("native BLAS publication failed");

        Thread drain = Thread.ofVirtual().name("failed-retained-owner-drain").start(() -> {
            try {
                geometry.drain();
            } catch (Throwable failure) {
                drainFailure.set(failure);
            }
        });
        assertTrue(backend.awaitProgress());
        assertTrue(drain.isAlive());

        backend.fail(fatal);
        drain.join(2_000L);

        assertFalse(drain.isAlive());
        assertSame(fatal, drainFailure.get());
    }

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

        channel.select(new EnvironmentBinding<>(environment, ENVIRONMENT_BINDING.data(7)));
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
        directory.progress();
        assertEquals(0, firstRetired.get());

        channel.invalidate();
        backend.retireLatest();
        directory.progress();
        channel.drain();
        assertEquals(null, directory.snapshot().scenes().getFirst().environment());
    }

    @Test
    void environmentSelectionsUseLatestOwnerPrecedenceAndRestoreTheLatestSurvivor() {
        ProgramFixture programs = new ProgramFixture();
        EnvironmentId<EnvironmentBindingData> environment =
                programs.environment(new ContributionOwner(1)).exports();
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        SceneEnvironmentContributionChannel first =
                directory.openEnvironment(new ContributionOwner(2), scene);
        SceneEnvironmentContributionChannel second =
                directory.openEnvironment(new ContributionOwner(3), scene);
        AtomicInteger firstOriginalRetired = new AtomicInteger();

        first.select(new EnvironmentBinding<>(environment, ENVIRONMENT_BINDING.data(10)));
        second.select(EnvironmentBinding.of(environment, ENVIRONMENT_BINDING.data(20)));
        assertEquals(20, selectedEnvironmentBits(directory));

        first.select(EnvironmentBinding.of(environment, ENVIRONMENT_BINDING.data(11)));
        assertEquals(11, selectedEnvironmentBits(directory));
        first.invalidate();
        assertEquals(20, selectedEnvironmentBits(directory));

        backend.retire(2);
        directory.progress();
        assertEquals(0, firstOriginalRetired.get());
        backend.retireLatest();
        directory.progress();
        first.drain();
        backend.retire(3);
        directory.progress();

        second.invalidate();
        assertEquals(null, directory.snapshot().scenes().getFirst().environment());
        backend.retireLatest();
        directory.progress();
        second.drain();
    }

    @Test
    void invalidatingDormantEnvironmentWaitsForItsOlderGpuSnapshot() {
        ProgramFixture programs = new ProgramFixture();
        EnvironmentId<EnvironmentBindingData> environment =
                programs.environment(new ContributionOwner(1)).exports();
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        SceneEnvironmentContributionChannel first =
                directory.openEnvironment(new ContributionOwner(2), scene);
        SceneEnvironmentContributionChannel second =
                directory.openEnvironment(new ContributionOwner(3), scene);
        AtomicInteger retired = new AtomicInteger();

        first.select(new EnvironmentBinding<>(environment, ENVIRONMENT_BINDING.data(10)));
        second.select(EnvironmentBinding.of(environment, ENVIRONMENT_BINDING.data(20)));
        first.invalidate();
        directory.progress();
        assertEquals(0, retired.get());

        backend.retire(2);
        directory.progress();
        first.drain();
        assertEquals(0, retired.get());
        assertEquals(20, selectedEnvironmentBits(directory));
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
    void groupedGeometryPublishesOneOrderedRevision() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        int publications = backend.snapshots.size();
        long revision = directory.snapshot().revision();
        GeometryTransform finalTransform = GeometryTransform.translation(4, 5, 6);

        geometry.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(1, 2, 3), 0xff, INSTANCE.data(7)))),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        finalTransform, 0xff, INSTANCE.data(8))))));

        assertEquals(publications + 1, backend.snapshots.size());
        assertEquals(revision + 1, directory.snapshot().revision());
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(1, directory.snapshot().instances().size());
        assertEquals(finalTransform, directory.snapshot().instances().getFirst().transform());
        assertEquals(8, directory.snapshot().instances().getFirst().instanceData().bits());
    }

    @Test
    void latestPlacementUpdatesLogicalAndBackendStateWithoutARevision() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(7)))));
        long revision = directory.snapshot().revision();

        geometry.submitGroupWithLatest(List.of(), List.of(new GeometryChannel.LatestInstance(
                instance, GeometryTransform.translation(3, 0, 0), 0x01)));

        assertEquals(revision, directory.snapshot().revision());
        assertEquals(GeometryTransform.translation(3, 0, 0),
                directory.snapshot().instances().getFirst().transform());
        assertEquals(0x01, directory.snapshot().instances().getFirst().mask());
        assertEquals(1, backend.latestTransforms.size());
        assertEquals(GeometryTransform.translation(3, 0, 0),
                backend.latestTransforms.getFirst().transform());
    }

    @Test
    void latestPlacementRequiresTheIssuingOwnerAndALivePlacement() {
        ProgramFixture programs = new ProgramFixture();
        SceneDirectory directory = directory(programs, new SceneBackend());
        directory.createScene();
        GeometryContributionChannel first = directory.openGeometry(new ContributionOwner(1));
        GeometryContributionChannel second = directory.openGeometry(new ContributionOwner(2));
        var instance = first.newInstance();

        assertThrows(IllegalArgumentException.class, () -> second.submitGroupWithLatest(
                List.of(), List.of(new GeometryChannel.LatestInstance(
                        instance, GeometryTransform.translation(1, 0, 0), 0xff))));
        assertThrows(IllegalArgumentException.class, () -> first.submitGroupWithLatest(
                List.of(), List.of(new GeometryChannel.LatestInstance(
                        instance, GeometryTransform.translation(1, 0, 0), 0xff))));
    }

    @Test
    void lightContentInterleavesWithFullGeometryInOneRevisionOrder() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        LightContributionChannel lights = directory.openLights(new ContributionOwner(3));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var light = lights.newLight();

        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));
        lights.submit(RetainedBatch.of(List.of(new LightChannel.SetLight(light, scene,
                new LightDescriptor.Spot(0, 1, 0, 0, -1, 0, 10, 0.5, 1, 1, 1)))));
        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))));

        assertEquals(List.of(1L, 2L, 3L, 4L), backend.revisions);
        assertEquals(3, backend.snapshots.size());
        assertEquals(1, backend.contentSnapshots.size());
        assertEquals(scene, backend.contentSnapshots.getFirst().scenes().getFirst().id());
        assertEquals(1, backend.contentSnapshots.getFirst().lights().size());
    }

    @Test
    void geometryReceiptBecomesVisibleOnlyWhenAsyncBackendPublishes() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        AsyncSceneBackend backend = new AsyncSceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        backend.completeAll();
        directory.progress();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);

        var publication = geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));

        assertFalse(publication.isVisible());
        backend.completeAll();
        directory.progress();
        assertTrue(publication.isVisible());
    }

    @Test
    void geometryReceiptRunsCallbacksRegisteredBeforeAndAfterVisibility() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        AsyncSceneBackend backend = new AsyncSceneBackend();
        SceneDirectory directory = directory(programs, backend);
        directory.createScene();
        backend.completeAll();
        directory.progress();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        AtomicInteger callbacks = new AtomicInteger();
        var publication = geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));

        publication.whenVisible(callbacks::incrementAndGet);
        assertEquals(0, callbacks.get());
        backend.completeAll();
        directory.progress();
        assertEquals(1, callbacks.get());

        publication.whenVisible(callbacks::incrementAndGet);
        assertEquals(2, callbacks.get());
    }

    @Test
    void environmentReceiptTracksNativeVisibilityAndRunsEarlyAndLateCallbacks() {
        ProgramFixture programs = new ProgramFixture();
        EnvironmentId<EnvironmentBindingData> environment =
                programs.environment(new ContributionOwner(1)).exports();
        AsyncSceneBackend backend = new AsyncSceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        backend.completeAll();
        directory.progress();
        SceneEnvironmentContributionChannel environments =
                directory.openEnvironment(new ContributionOwner(2), scene);
        AtomicInteger callbacks = new AtomicInteger();

        var publication = environments.select(
                EnvironmentBinding.of(environment, ENVIRONMENT_BINDING.data(7)));
        publication.whenVisible(callbacks::incrementAndGet);

        assertFalse(publication.isVisible());
        assertEquals(0, callbacks.get());
        backend.completeAll();
        directory.progress();
        assertTrue(publication.isVisible());
        assertEquals(1, callbacks.get());

        publication.whenVisible(callbacks::incrementAndGet);
        assertEquals(2, callbacks.get());
    }

    @Test
    void visibilityCallbackFailureIsReportedWithoutBlockingOtherCallbacksOrProgress() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        AsyncSceneBackend backend = new AsyncSceneBackend();
        List<Throwable> failures = new ArrayList<>();
        SceneDirectory directory = new SceneDirectory(programs.session, programs.resources, backend,
                failures::add);
        directory.createScene();
        backend.completeAll();
        directory.progress();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        AtomicInteger callbacks = new AtomicInteger();
        RuntimeException callbackFailure = new RuntimeException("visibility callback");
        var publication = geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)))));
        publication.whenVisible(() -> { throw callbackFailure; });
        publication.whenVisible(callbacks::incrementAndGet);

        backend.completeAll();
        directory.progress();

        assertEquals(List.of(callbackFailure), failures);
        assertEquals(1, callbacks.get());
        assertTrue(publication.isVisible());

        RuntimeException lateFailure = new RuntimeException("late visibility callback");
        publication.whenVisible(() -> { throw lateFailure; });
        publication.whenVisible(callbacks::incrementAndGet);
        assertEquals(List.of(callbackFailure, lateFailure), failures);
        assertEquals(2, callbacks.get());
    }

    @Test
    void groupedGeometryPreservesIndependentRetirementLifetimes() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        AtomicInteger meshRetired = new AtomicInteger();
        AtomicInteger instanceRetired = new AtomicInteger();

        geometry.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(1, 2, 3), 0xff, INSTANCE.data(7))))));

        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropInstance(instance))));
        backend.retireLatest();
        directory.progress();
        assertEquals(0, meshRetired.get());
        assertEquals(0, instanceRetired.get());

        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))));
        backend.retireLatest();
        directory.progress();
        assertEquals(0, meshRetired.get());
        assertEquals(0, instanceRetired.get());
    }

    @Test
    void invalidLaterGroupedBatchRejectsEverythingWithoutTakingCallbacks() {
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
        long revision = directory.snapshot().revision();

        assertThrows(IllegalArgumentException.class, () -> geometry.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, foreignScene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0)))))));

        assertEquals(publications, backend.snapshots.size());
        assertEquals(revision, directory.snapshot().revision());
        assertEquals(0, directory.snapshot().meshes().size());
        directory.progress();
        assertEquals(0, retired.get());
    }

    @Test
    void backendRejectionOfGroupedGeometryTransfersNothing() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel geometry = directory.openGeometry(new ContributionOwner(2));
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        AtomicInteger retired = new AtomicInteger();
        int publications = backend.snapshots.size();
        backend.rejectNext = true;

        assertThrows(IllegalStateException.class, () -> geometry.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0)))))));

        assertEquals(publications, backend.snapshots.size());
        assertEquals(0, directory.snapshot().meshes().size());
        assertEquals(0, directory.snapshot().instances().size());
        directory.progress();
        assertEquals(0, retired.get());
    }

    @Test
    void groupedGeometryKeepsMutationOwnerLocalAndLightSelectionSessionScoped() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        GeometryContributionChannel owner = directory.openGeometry(new ContributionOwner(2));
        GeometryContributionChannel foreign = directory.openGeometry(new ContributionOwner(3));
        LightContributionChannel lights = directory.openLights(new ContributionOwner(4));
        MeshId<Instance> mesh = owner.newMesh(INSTANCE);
        var instance = owner.newInstance();
        var selectedLight = lights.newLight();

        owner.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface)))),
                RetainedBatch.of(List.of(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0),
                        new PrimitiveLightMap(List.of(new PrimitiveLightMap.Range(0, 1, selectedLight))))))));

        assertEquals(1, directory.snapshot().instances().getFirst().primitiveEmitters().size());
        assertThrows(IllegalArgumentException.class, () -> foreign.submitGroup(List.of(
                RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))),
                RetainedBatch.of(List.of(new GeometryChannel.DropInstance(instance))))));
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(1, directory.snapshot().instances().size());
    }

    @Test
    void geometryAndLightsPublishInOneRevisionAndRetireTheirBatchesIndependently() {
        ProgramFixture programs = new ProgramFixture();
        SurfaceId<Binding, Instance> surface = programs.surface(new ContributionOwner(1));
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        ContributionOwner owner = new ContributionOwner(2);
        GeometryContributionChannel geometry = directory.openGeometry(owner);
        LightContributionChannel lights = directory.openLights(owner);
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var instance = geometry.newInstance();
        var light = lights.newLight();
        AtomicInteger geometryRetired = new AtomicInteger();
        AtomicInteger lightRetired = new AtomicInteger();

        geometry.submitWithLights(List.of(RetainedBatch.of(List.of(
                        new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                        new GeometryChannel.SetInstance<>(instance, scene, mesh,
                                GeometryTransform.translation(0, 0, 0), 0xff, INSTANCE.data(0),
                                new PrimitiveLightMap(List.of(
                                        new PrimitiveLightMap.Range(0, 1, light))))))), lights,
                RetainedBatch.of(List.of(new LightChannel.SetLight(light, scene,
                        new LightDescriptor.Parallelogram(
                                0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1)))));

        RetainedSceneSnapshot combined = backend.snapshots.getLast();
        assertEquals(1, combined.instances().size());
        assertEquals(1, combined.lights().size());
        assertEquals(combined.lights().getFirst().identity(),
                combined.instances().getFirst().primitiveEmitters().getFirst().lightIdentity());
        geometry.submitWithLights(List.of(RetainedBatch.of(List.of(
                        new GeometryChannel.DropInstance(instance),
                        new GeometryChannel.DropMesh<>(mesh)))), lights,
                RetainedBatch.of(List.of(new LightChannel.DropLight(light))));
        backend.retireLatest();
        directory.progress();

        assertEquals(0, geometryRetired.get());
        assertEquals(0, lightRetired.get());
    }

    @Test
    void combinedPublicationRequiresChannelsFromTheSameContributionAndSession() {
        ProgramFixture programs = new ProgramFixture();
        SceneDirectory directory = directory(programs, new SceneBackend());
        ContributionOwner owner = new ContributionOwner(1);
        GeometryContributionChannel geometry = directory.openGeometry(owner);
        LightContributionChannel otherOwner = directory.openLights(new ContributionOwner(2));
        LightContributionChannel sameOwner = directory.openLights(owner);
        LightContributionChannel foreignSession = directory(new ProgramFixture(), new SceneBackend())
                .openLights(owner);
        MeshId<Instance> mesh = geometry.newMesh(INSTANCE);
        var geometryBatch = RetainedBatch.<GeometryChannel.Operation>of(List.of(
                new GeometryChannel.DropMesh<>(mesh)));
        var otherLight = otherOwner.newLight();
        var sameLight = sameOwner.newLight();
        var foreignLight = foreignSession.newLight();

        assertThrows(IllegalArgumentException.class, () -> geometry.submitWithLights(
                List.of(geometryBatch), otherOwner,
                RetainedBatch.of(List.of(new LightChannel.DropLight(otherLight)))));
        assertThrows(IllegalArgumentException.class, () -> geometry.submitWithLights(
                List.of(geometryBatch), foreignSession,
                RetainedBatch.of(List.of(new LightChannel.DropLight(foreignLight)))));
        assertEquals(0, directory.snapshot().meshes().size());
        assertThrows(IllegalArgumentException.class, () -> geometry.submitWithLights(
                List.of(), sameOwner,
                RetainedBatch.of(List.of(new LightChannel.DropLight(sameLight)))));
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

        assertThrows(IllegalArgumentException.class, () -> geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(instance, foreignScene, mesh, GeometryTransform.translation(0, 0, 0),
                        0xff, INSTANCE.data(0))))));

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

        assertThrows(IllegalStateException.class, () -> geometry.submit(RetainedBatch.of(
                List.of(new GeometryChannel.SetMesh<>(mesh, mesh(surface))))));

        assertEquals(0, directory.snapshot().meshes().size());
        directory.progress();
        assertEquals(0, retired.get());
    }

    @Test
    void rejectedContentPublicationKeepsLightStateAndRetirementRetryable() {
        ProgramFixture programs = new ProgramFixture();
        SceneBackend backend = new SceneBackend();
        SceneDirectory directory = directory(programs, backend);
        SceneId scene = directory.createScene();
        LightContributionChannel lights = directory.openLights(new ContributionOwner(1));
        var light = lights.newLight();
        AtomicInteger retired = new AtomicInteger();
        backend.rejectNext = true;

        assertThrows(IllegalStateException.class, () -> lights.submit(RetainedBatch.of(List.of(
                new LightChannel.SetLight(light, scene,
                        new LightDescriptor.Spot(0, 1, 0, 0, -1, 0, 10, 0.5, 1, 1, 1))))));

        assertEquals(0, directory.snapshot().lights().size());
        directory.progress();
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
        geometry.submit(RetainedBatch.of(List.of(
                new GeometryChannel.SetMesh<>(mesh, mesh(surface)),
                new GeometryChannel.SetInstance<>(instance, scene, mesh, GeometryTransform.translation(0, 0, 0),
                        0xff, INSTANCE.data(0)))));
        lights.submit(RetainedBatch.of(List.of(new LightChannel.SetLight(light, scene,
                new LightDescriptor.Spot(0, 1, 0, 0, -1, 0, 10, 0.5, 1, 1, 1)))));

        directory.dropScene(scene);
        assertEquals(1, directory.snapshot().meshes().size());
        assertEquals(0, directory.snapshot().instances().size());
        assertEquals(0, directory.snapshot().lights().size());
        backend.retireLatest();
        directory.progress();
        assertEquals(0, geometryRetired.get(), "mesh from the same batch is still retained");
        assertEquals(0, lightRetired.get());

        geometry.submit(RetainedBatch.of(List.of(new GeometryChannel.DropMesh<>(mesh))));
        backend.retireLatest();
        directory.progress();
        assertEquals(0, geometryRetired.get());
    }

    private static MeshBuild<Instance> mesh(SurfaceId<Binding, Instance> surface) {
        return mesh(surface, null, ResourceRef.none(), ResourceRef.none(),
                ResourceRef.none(), ResourceRef.none());
    }

    private static MeshBuild<Instance> mesh(
            SurfaceId<Binding, Instance> surface, VolumeId<Binding, Instance> volume,
            ResourceRef positionsResource, ResourceRef indicesResource,
            ResourceRef surfaceResource, ResourceRef volumeResource) {
        MeshBuild.Stream positions = new MeshBuild.Stream(
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x1000), 36), 12,
                positionsResource);
        MeshBuild.Stream indices = new MeshBuild.Stream(
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x2000), 12), 4,
                indicesResource);
        var surfaceSlot = new MeshBuild.SurfaceSlot<>(surface, BINDING.data(0, surfaceResource),
                new MeshBuild.CoveragePolicy.Opaque());
        MeshBuild.VolumeSlot<Binding, Instance> volumeSlot = volume == null
                ? null : new MeshBuild.VolumeSlot<>(volume, BINDING.data(0, volumeResource));
        return new MeshBuild<>(positions, indices, 3, new MeshBuild.IndexRevision(1),
                List.of(new MeshBuild.Geometry<>(surfaceSlot, volumeSlot, 0, 3)));
    }

    private static long selectedEnvironmentBits(SceneDirectory directory) {
        return directory.snapshot().scenes().getFirst().environment().bindingData().bits();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EnvironmentBinding<?> mismatchedBinding(EnvironmentId<?> environment) {
        return new EnvironmentBinding((EnvironmentId) environment,
                ShaderDataType.create("wrong").data(0));
    }

    private static SceneDirectory directory(ProgramFixture programs, RetainedSceneBackend backend) {
        return directory(programs, programs.resources, backend);
    }

    private static SceneDirectory directory(ProgramFixture programs, ResourceDirectory resources,
                                            RetainedSceneBackend backend) {
        return new SceneDirectory(programs.session, resources, backend,
                failure -> { throw new AssertionError(failure); });
    }

    private static final class ProgramFixture {
        private final ImmediateProgramBackend backend = new ImmediateProgramBackend();
        private final ResourceDirectory resources = new ResourceDirectory(
                failure -> { throw new AssertionError(failure); });
        private final ProgramSession session = new ProgramSession(
                resources, backend, failure -> { throw new AssertionError(failure); });
        SurfaceId<Binding, Instance> surface(ContributionOwner owner) {
            ProgramContributionChannel channel = session.openChannel(owner);
            var registration = channel.register(builder -> builder.surface(new SurfaceDefinition<>(
                    new ShaderDefinition(ShaderSource.classpath(SceneDirectoryTest.class, "/shaders"),
                            "surface", "test.Surface"), null, IMPLEMENTATION.data(0), BINDING, INSTANCE)));
            session.progress();
            session.progress();
            return registration.exports();
        }
        VolumeId<Binding, Instance> volume(ContributionOwner owner) {
            ProgramContributionChannel channel = session.openChannel(owner);
            var registration = channel.register(builder -> builder.volume(new VolumeDefinition<>(
                    new ShaderDefinition(ShaderSource.classpath(SceneDirectoryTest.class, "/shaders"),
                            "volume", "test.Volume"), IMPLEMENTATION.data(0), BINDING, INSTANCE)));
            session.progress();
            session.progress();
            return registration.exports();
        }
        ProgramRegistration<EnvironmentId<EnvironmentBindingData>> environment(ContributionOwner owner) {
            ProgramContributionChannel channel = session.openChannel(owner);
            ProgramRegistration<EnvironmentId<EnvironmentBindingData>> registration = channel.register(
                    builder -> builder.environment(new EnvironmentDefinition<>(new ShaderDefinition(
                            ShaderSource.classpath(SceneDirectoryTest.class, "/shaders"),
                            "environment", "test.Environment"), ENVIRONMENT_BINDING)));
            session.progress();
            session.progress();
            return registration;
        }
    }

    private static final class ImmediateProgramBackend implements ProgramBackend {
        @Override public void compile(ProgramComposition composition,
                                      java.util.function.Consumer<? super Compilation> completion) {
            completion.accept(new Compilation.Succeeded(program()));
        }
        @Override public void publish(CompiledProgram program, Runnable previousRetired) { previousRetired.run(); }
        @Override public void drainPublishedUses() { }

        private static CompiledProgram program() {
            return new CompiledProgram() {
                @Override public void close() { }
            };
        }
    }

    private static final class SceneBackend implements RetainedSceneBackend {
        private final List<RetainedSceneSnapshot> snapshots = new ArrayList<>();
        private final List<RetainedSceneContentSnapshot> contentSnapshots = new ArrayList<>();
        private final List<Long> revisions = new ArrayList<>();
        private final List<Runnable> retirements = new ArrayList<>();
        private final List<RetainedInstanceTransform> latestTransforms = new ArrayList<>();
        private boolean rejectNext;
        @Override public void publish(RetainedSceneSnapshot snapshot, Runnable published) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected native publication");
            }
            snapshots.add(snapshot); revisions.add(snapshot.revision());
            published.run(); retirements.add(() -> { });
        }
        @Override public void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("rejected native publication");
            }
            contentSnapshots.add(snapshot); revisions.add(snapshot.revision());
            published.run(); retirements.add(() -> { });
        }
        @Override public void updateLatestInstanceTransforms(List<RetainedInstanceTransform> transforms) {
            latestTransforms.addAll(transforms);
        }
        void retire(int publication) { retirements.get(publication).run(); }
        void retireLatest() { retirements.getLast().run(); }
    }

    private static final class AsyncSceneBackend implements RetainedSceneBackend {
        private final List<Runnable> pending = new ArrayList<>();
        private final List<Runnable> completed = new ArrayList<>();
        private final CountDownLatch progressAttempted = new CountDownLatch(1);
        private Runnable progressAvailable = () -> { };
        private Throwable fatalFailure;

        @Override
        public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable published) {
            pending.add(published);
        }

        @Override
        public synchronized void publishContent(RetainedSceneContentSnapshot snapshot, Runnable published) {
            pending.add(published);
        }

        @Override
        public synchronized void onProgressAvailable(Runnable wakeup) {
            progressAvailable = wakeup;
        }

        @Override
        public void prepareForSessionClose() {
            completeAll();
        }

        @Override
        public void progress() {
            List<Runnable> retirements;
            Throwable fatal;
            synchronized (this) {
                progressAttempted.countDown();
                fatal = fatalFailure;
                retirements = List.copyOf(completed);
                completed.clear();
            }
            if (fatal instanceof RuntimeException runtime) throw runtime;
            if (fatal instanceof Error error) throw error;
            retirements.forEach(Runnable::run);
        }

        boolean awaitProgress() throws InterruptedException {
            return progressAttempted.await(2, TimeUnit.SECONDS);
        }

        void completeAll() {
            Runnable signal;
            synchronized (this) {
                completed.addAll(pending);
                pending.clear();
                signal = progressAvailable;
            }
            signal.run();
        }

        void fail(Throwable failure) {
            Runnable signal;
            synchronized (this) {
                fatalFailure = failure;
                signal = progressAvailable;
            }
            signal.run();
        }
    }
}
