package dev.comfyfluffy.caustica.example.showcase;
import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.*;
import dev.comfyfluffy.caustica.api.vulkan.*;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ShowcaseSceneApiTest {
    @Test void rejectedRemovalStillReleasesTheProducerMeshClaim() {
        var engine = new TestScene();
        var retired = new AtomicBoolean();
        var scene = new ShowcaseScene(exports(), lights(), new SceneId() {}, engine, engine);
        scene.publishMesh(range(0x1000, 48), range(0x3000, 48),
                TestResource.create(() -> retired.set(true))).join();
        engine.rejectNext = true;
        assertThrows(IllegalArgumentException.class, scene::stop);
        assertTrue(retired.get());
        scene.stop();
    }

    @Test void preparationDoesNotMutateSceneUntilReady() {
        var engine=new TestScene(); engine.delayed=true;
        var retired=new AtomicBoolean();
        var scene=new ShowcaseScene(exports(),lights(),new SceneId(){},engine,engine);
        var pending=scene.publishMesh(range(0x1000,48),range(0x3000,48),TestResource.create(()->retired.set(true)));
        assertTrue(engine.batches.isEmpty()); assertFalse(pending.isDone()); assertFalse(retired.get());
        engine.complete(); pending.join();
        var placed=assertInstanceOf(SceneEdit.SetInstance.class,engine.last().getFirst());
        assertEquals(1,engine.last().size());
        var frame=placed.mesh().retain();
        scene.stop(); assertFalse(retired.get()); frame.close(); assertTrue(retired.get());
    }
    @Test void replacementUsesLatestPlacementAndLeavesOldMeshUntilReady() {
        var engine=new TestScene();
        var scene=new ShowcaseScene(exports(),lights(),new SceneId(){},engine,engine);
        var retired=new AtomicBoolean();
        scene.publishMesh(range(0x1000,48),range(0x3000,48),TestResource.create(()->retired.set(true))).join();
        var first=(SceneEdit.SetInstance<?>)engine.last().getFirst();
        var frame=first.mesh().retain(); engine.delayed=true;
        var pending=scene.replaceMesh(range(0x4000,48),range(0x6000,48),2,TestResource.create(()->{}));
        var target=new SceneId(){};
        scene.moveInstance(target,GeometryTransform.translation(4,70,-3));
        assertSame(first.mesh(),((SceneEdit.SetInstance<?>)engine.last().getFirst()).mesh());
        engine.complete();pending.join();
        var next=(SceneEdit.SetInstance<?>)engine.last().getFirst();
        assertSame(first.instance(),next.instance());assertSame(target,next.scene());
        assertEquals(4,next.transform().translationX());assertFalse(retired.get());
        frame.close();assertTrue(retired.get());scene.stop();
    }
    @Test void obsoletePreparationCannotResurrectStoppedScene() {
        var engine=new TestScene();engine.delayed=true;
        var retired=new AtomicBoolean();
        var scene=new ShowcaseScene(exports(),lights(),new SceneId(){},engine,engine);
        scene.publishMesh(range(0x1000,48),range(0x3000,48),TestResource.create(()->retired.set(true)));
        scene.stop();engine.complete();assertTrue(engine.batches.isEmpty());assertTrue(retired.get());
    }
    @Test void rejectedReadyReplacementReleasesOnlyItsNewOwnership() {
        var engine=new TestScene();
        var scene=new ShowcaseScene(exports(),lights(),new SceneId(){},engine,engine);
        var old=new AtomicBoolean();var rejected=new AtomicBoolean();
        scene.publishMesh(range(0x1000,48),range(0x3000,48),TestResource.create(()->old.set(true))).join();
        engine.rejectNext=true;
        assertThrows(CompletionException.class,()->scene.replaceMesh(range(0x4000,48),range(0x6000,48),2,
                TestResource.create(()->rejected.set(true))).join());
        assertTrue(rejected.get());assertFalse(old.get());scene.stop();assertTrue(old.get());
    }
    private static ShowcasePrograms.Exports exports() {
        SurfaceId<ShowcasePrograms.SurfaceBindingData, ShowcasePrograms.InstanceData> opaque =
                new SurfaceId<>() { };
        SurfaceId<ShowcasePrograms.SurfaceBindingData, ShowcasePrograms.InstanceData> cutout =
                new SurfaceId<>() { };
        VolumeId<ShowcasePrograms.VolumeBindingData, ShowcasePrograms.InstanceData> volume =
                new VolumeId<>() { };
        EnvironmentId<ShowcasePrograms.EnvironmentBindingData> environment = new EnvironmentId<>() { };
        return new ShowcasePrograms.Exports(opaque, cutout, volume, environment, environment, environment);
    }

    private static VulkanDeviceAddressRange range(long address, long bytes) {
        return new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes);
    }

    private static List<LightId> lights() {
        return List.of(new LightId() { }, new LightId() { }, new LightId() { });
    }

}
