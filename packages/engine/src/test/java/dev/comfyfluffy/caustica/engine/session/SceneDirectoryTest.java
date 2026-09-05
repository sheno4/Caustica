package dev.comfyfluffy.caustica.engine.session;
import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.light.*;
import dev.comfyfluffy.caustica.api.program.*;
import dev.comfyfluffy.caustica.api.resource.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.engine.program.*;
import dev.comfyfluffy.caustica.engine.resource.*;
import dev.comfyfluffy.caustica.engine.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class SceneDirectoryTest {
    interface Implementation { } interface Binding { } interface Instance { } interface EnvironmentBindingData { }
    private static final ShaderDataType<Implementation> IMPLEMENTATION=ShaderDataType.create("impl");
    private static final ShaderDataType<Binding> BINDING=ShaderDataType.create("binding");
    private static final ShaderDataType<Instance> INSTANCE=ShaderDataType.create("instance");
    private static final ShaderDataType<EnvironmentBindingData> ENVIRONMENT_BINDING=ShaderDataType.create("environment");

    @Test void preparationDoesNotPublishAndEditPublishesWholeGroupImmediately() {
        var f=new Fixture();var pending=new CompletableFuture<ResourceOwner>();f.preparing=pending;
        var first=f.channel.prepare(INSTANCE,mesh(f.surface));
        assertFalse(first.isDone());assertEquals(0,f.directory.snapshot().meshes().size());
        pending.complete(nativeOwner(new AtomicInteger()));var ready=first.join();
        assertEquals(0,f.directory.snapshot().meshes().size());
        var a=f.channel.newInstance();var b=f.channel.newInstance();
        long before=f.directory.snapshot().revision();
        f.channel.edit(List.of(set(a,f.scene,ready),set(b,f.scene,ready)));
        assertEquals(before+1,f.directory.snapshot().revision());
        assertEquals(2,f.directory.snapshot().instances().size());assertEquals(1,f.directory.snapshot().meshes().size());
    }
    @Test void failingOperationRollsBackCompleteEdit() {
        var f=new Fixture();var ready=f.channel.prepare(INSTANCE,mesh(f.surface)).join();
        var own=f.channel.newInstance();var other=f.directory.openChannel(new ContributionOwner(3));
        var foreign=other.newInstance();long before=f.directory.snapshot().revision();
        assertThrows(IllegalArgumentException.class,()->f.channel.edit(List.of(set(own,f.scene,ready),new SceneEdit.DropInstance(foreign))));
        assertEquals(before,f.directory.snapshot().revision());assertTrue(f.directory.snapshot().instances().isEmpty());
    }
    @Test void sharedReadyMeshSurvivesProducerAndOriginatingScope() {
        var f=new Fixture();var destroyed=new AtomicInteger();f.preparing=CompletableFuture.completedFuture(nativeOwner(destroyed));
        var ready=f.channel.prepare(INSTANCE,mesh(f.surface)).join();
        var consumer=f.directory.openChannel(new ContributionOwner(3));var instance=consumer.newInstance();
        consumer.edit(List.of(set(instance,f.scene,ready)));ready.close();f.channel.invalidate();
        assertEquals(0,destroyed.get());
        consumer.edit(List.of(new SceneEdit.SetTransform(instance,GeometryTransform.translation(0,0,0),7)));
        assertEquals(7,f.directory.snapshot().instances().getFirst().mask());
        consumer.edit(List.of(new SceneEdit.DropInstance(instance)));assertEquals(1,destroyed.get());
    }
    @Test void cancelledPreparationStillReleasesCompletedNativeMesh() {
        var f=new Fixture();var pending=new CompletableFuture<ResourceOwner>();f.preparing=pending;
        var result=f.channel.prepare(INSTANCE,mesh(f.surface));assertTrue(result.cancel(false));
        var destroyed=new AtomicInteger();pending.complete(nativeOwner(destroyed));f.channel.drain();
        assertEquals(1,destroyed.get());assertTrue(f.directory.snapshot().instances().isEmpty());
    }
    @Test void invalidatedPreparationCannotRestoreContent() {
        var f=new Fixture();var pending=new CompletableFuture<ResourceOwner>();f.preparing=pending;
        var result=f.channel.prepare(INSTANCE,mesh(f.surface));f.channel.invalidate();
        var destroyed=new AtomicInteger();pending.complete(nativeOwner(destroyed));f.channel.drain();
        assertTrue(result.isCompletedExceptionally());assertEquals(1,destroyed.get());
    }
    @Test void inputResourcesRemainAliveThroughPreparationAndReadyUse() {
        var f=new Fixture();var owner=new ContributionOwner(9);var destroyed=new AtomicInteger();
        var resource=f.programs.resources.openFactory(owner).create(destroyed::incrementAndGet);
        var pending=new CompletableFuture<ResourceOwner>();f.preparing=pending;
        var result=f.channel.prepare(INSTANCE,mesh(f.surface,null,resource.reference(),ResourceRef.none(),ResourceRef.none(),ResourceRef.none()));
        resource.close();f.programs.resources.awaitRetirements();assertEquals(0,destroyed.get());
        pending.complete(nativeOwner(new AtomicInteger()));result.join().close();f.programs.resources.awaitRetirements();assertEquals(1,destroyed.get());
    }
    @Test void sceneRemovalOnlyRemovesItsOwnPlacements() {
        var f=new Fixture();var other=f.directory.createScene();var ready=f.channel.prepare(INSTANCE,mesh(f.surface)).join();
        f.channel.edit(List.of(set(f.channel.newInstance(),f.scene,ready),set(f.channel.newInstance(),other,ready)));
        f.directory.dropScene(f.scene);assertEquals(1,f.directory.snapshot().instances().size());
        assertSame(other,f.directory.snapshot().instances().getFirst().scene());
    }
    @Test void environmentScopesRestoreMostRecentSurvivingSelection() {
        var f=new Fixture();var environment=f.programs.environment(new ContributionOwner(6)).exports();
        var a=f.directory.openEnvironment(new ContributionOwner(7),f.scene);
        var b=f.directory.openEnvironment(new ContributionOwner(8),f.scene);
        a.select(new EnvironmentBinding<>(environment,ENVIRONMENT_BINDING.data(1)));
        b.select(new EnvironmentBinding<>(environment,ENVIRONMENT_BINDING.data(2)));
        a.select(new EnvironmentBinding<>(environment,ENVIRONMENT_BINDING.data(3)));
        a.invalidate();assertEquals(2,f.directory.snapshot().scenes().getFirst().environment().bindingData().bits());
        b.invalidate();assertNull(f.directory.snapshot().scenes().getFirst().environment());
    }
    @Test void mixedInstancesLightsAndEnvironmentShareOneRevision() {
        var f=new Fixture();var ready=f.channel.prepare(INSTANCE,mesh(f.surface)).join();
        var environment=f.programs.environment(new ContributionOwner(6)).exports();
        var scene2=f.directory.createScene();long before=f.directory.snapshot().revision();
        f.channel.edit(List.of(set(f.channel.newInstance(),f.scene,ready),
            new SceneEdit.SetLight(f.channel.newLight(),scene2,new LightDescriptor.Distant(0,1,0,1,1,1,0,false)),
            new SceneEdit.SetEnvironment(scene2,new EnvironmentBinding<>(environment,ENVIRONMENT_BINDING.data(10)))));
        var snapshot=f.directory.snapshot();assertEquals(before+1,snapshot.revision());
        assertEquals(1,snapshot.instances().size());assertEquals(1,snapshot.lights().size());
        assertEquals(10,snapshot.scenes().getLast().environment().bindingData().bits());
    }
    @Test void capturedFramesOwnResourcesAcrossReplacementAndProducerInvalidation() {
        var f = new Fixture();
        var destroyed = new AtomicInteger();
        var nativeDestroyed = new AtomicInteger();
        var retirementThread = new AtomicReference<Thread>();
        var resource = f.programs.resources.openFactory(new ContributionOwner(9)).create(() -> {
            retirementThread.set(Thread.currentThread());
            destroyed.incrementAndGet();
        });
        f.preparing = CompletableFuture.completedFuture(nativeOwner(nativeDestroyed));
        var ready = f.channel.prepare(INSTANCE, mesh(f.surface, null, resource.reference(),
                resource.reference(), resource.reference(), ResourceRef.none())).join();
        var id = f.channel.newInstance();
        f.channel.edit(List.of(set(id, f.scene, ready)));
        var first = f.capture.get();
        var second = first.retain();
        ready.close();
        resource.close();
        f.channel.edit(List.of(new SceneEdit.SetTransform(id, GeometryTransform.translation(5, 0, 0), 7)));
        try (var next = f.capture.get()) {
            assertEquals(255, first.get().instances().getFirst().mask());
            assertEquals(7, next.get().instances().getFirst().mask());
        }
        f.channel.invalidate();
        f.directory.dropScene(f.scene);
        first.close();
        f.programs.resources.awaitRetirements();
        assertEquals(0, nativeDestroyed.get());
        assertEquals(0, destroyed.get());
        try (var claim = second.get().meshes().getFirst().ready().retain()) {
            assertSame(INSTANCE, claim.instanceDataType());
        }
        second.close();
        f.programs.resources.awaitRetirements();
        assertEquals(1, nativeDestroyed.get());
        assertEquals(1, destroyed.get());
        assertNotSame(Thread.currentThread(), retirementThread.get());
    }

    @Test void placementIdentityChangesOnlyAfterRemoval() {
        var f = new Fixture();
        var ready = f.channel.prepare(INSTANCE, mesh(f.surface)).join();
        var id = f.channel.newInstance();
        f.channel.edit(List.of(set(id, f.scene, ready)));
        long original = f.directory.snapshot().instances().getFirst().placementOrdinal();
        f.channel.edit(List.of(set(id, f.scene, ready)));
        assertEquals(original, f.directory.snapshot().instances().getFirst().placementOrdinal());
        f.channel.edit(List.of(new SceneEdit.DropInstance(id), set(id, f.scene, ready)));
        assertNotEquals(original, f.directory.snapshot().instances().getFirst().placementOrdinal());
    }

    @Test void capturedInstanceAndEnvironmentDataOutliveTheirScene() {
        var f = new Fixture();
        var factory = f.programs.resources.openFactory(new ContributionOwner(9));
        var instanceDestroyed = new AtomicInteger();
        var environmentDestroyed = new AtomicInteger();
        var data = factory.create(instanceDestroyed::incrementAndGet);
        var skyData = factory.create(environmentDestroyed::incrementAndGet);
        var environment = f.programs.environment(new ContributionOwner(6)).exports();
        var ready = f.channel.prepare(INSTANCE, mesh(f.surface)).join();
        f.channel.edit(List.of(new SceneEdit.SetInstance<>(f.channel.newInstance(), f.scene, ready,
                        GeometryTransform.translation(0, 0, 0), 255, INSTANCE.data(17, data.reference())),
                new SceneEdit.SetEnvironment(f.scene,
                        new EnvironmentBinding<>(environment, ENVIRONMENT_BINDING.data(23, skyData.reference())))));
        var captured = f.capture.get();
        data.close();
        skyData.close();
        ready.close();
        f.directory.dropScene(f.scene);
        f.programs.resources.awaitRetirements();
        assertEquals(0, instanceDestroyed.get());
        assertEquals(0, environmentDestroyed.get());
        assertEquals(17, captured.get().instances().getFirst().instanceData().bits());
        assertEquals(23, captured.get().scenes().getFirst().environment().bindingData().bits());
        captured.close();
        f.programs.resources.awaitRetirements();
        assertEquals(1, instanceDestroyed.get());
        assertEquals(1, environmentDestroyed.get());
    }

    @Test void invalidFinalOperationPreservesMixedEditAndExistingOwnership() {
        var f = new Fixture();
        var ready = f.channel.prepare(INSTANCE, mesh(f.surface)).join();
        var id = f.channel.newInstance();
        f.channel.edit(List.of(set(id, f.scene, ready)));
        ready.close();
        long revision = f.directory.snapshot().revision();
        assertThrows(IllegalArgumentException.class, () -> f.channel.edit(List.of(
                new SceneEdit.SetTransform(id, GeometryTransform.translation(5, 0, 0), 7),
                new SceneEdit.SetLight(f.channel.newLight(), f.scene,
                        new LightDescriptor.Distant(0, 1, 0, 1, 1, 1, 0, false)),
                new SceneEdit.SetTransform(f.channel.newInstance(), GeometryTransform.translation(0, 0, 0), 7))));
        try (var frame = f.capture.get()) {
            assertEquals(revision, frame.get().revision());
            assertEquals(255, frame.get().instances().getFirst().mask());
            assertTrue(frame.get().lights().isEmpty());
        }
    }

    @Test void capturesCannotObservePartOfCrossSceneEditGroup() throws Exception {
        var f = new Fixture();
        var ready = f.channel.prepare(INSTANCE, mesh(f.surface)).join();
        var other = f.directory.createScene();
        var a = f.channel.newInstance();
        var b = f.channel.newInstance();
        f.channel.edit(List.of(set(a, f.scene, ready), set(b, other, ready)));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var editing = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 300; i++) {
                    f.channel.edit(List.of(
                            new SceneEdit.SetTransform(a, GeometryTransform.translation(i, 0, 0), i % 256),
                            new SceneEdit.SetTransform(b, GeometryTransform.translation(i, 0, 0), i % 256)));
                }
                return null;
            });
            start.countDown();
            for (int i = 0; i < 300; i++) {
                try (var frame = f.capture.get()) {
                    var instances = frame.get().instances();
                    assertEquals(2, instances.size());
                    assertEquals(instances.getFirst().mask(), instances.getLast().mask());
                    assertEquals(instances.getFirst().transform(), instances.getLast().transform());
                }
            }
            editing.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void preparationRejectsWrongSchemaBeforeCallingBackend() {
        var f=new Fixture();
        assertThrows(IllegalArgumentException.class,()->f.channel.prepare((ShaderDataType)ShaderDataType.create("wrong"),(MeshBuild)mesh(f.surface)));
    }
    private static SceneEdit.SetInstance<Instance> set(InstanceId id,SceneId scene,ReadyMesh<Instance> mesh) {
        return new SceneEdit.SetInstance<>(id,scene,mesh,GeometryTransform.translation(0,0,0),255,INSTANCE.data(0));
    }
    private static ResourceOwner nativeOwner(AtomicInteger destroyed) {
        return new ResourceOwner() {
            public ResourceRef reference(){return ResourceRef.none();}
            public ResourceOwner retain(){throw new AssertionError("engine owns the native claim");}
            public void close(){destroyed.incrementAndGet();}
        };
    }
    private static final class Fixture {
        final ProgramFixture programs=new ProgramFixture();
        final SurfaceId<Binding,Instance> surface=programs.surface(new ContributionOwner(1));
        CompletableFuture<ResourceOwner> preparing;
        java.util.function.Supplier<dev.comfyfluffy.caustica.support.SharedResource<RetainedSceneSnapshot>> capture;
        final SceneDirectory directory=new SceneDirectory(programs.session,programs.resources,capture -> this.capture = capture,
            (mesh,source)->preparing==null?CompletableFuture.completedFuture(nativeOwner(new AtomicInteger())):preparing);
        final SceneId scene=directory.createScene();
        final SceneContributionChannel channel=directory.openChannel(new ContributionOwner(2));
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

}
