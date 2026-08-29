package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ShowcaseViewContractTest {
    @Test
    void vacuumAndUnderwaterViewsKeepTheHostSceneIdentity() {
        SceneId scene = new SceneId() { };
        Camera camera = Camera.IDENTITY;
        var bindingType = ShaderDataType.<Binding>create("underwater binding");
        var instanceType = ShaderDataType.<Instance>create("underwater instance");
        VolumeId<Binding, Instance> water = new VolumeId<>() { };

        var vacuum = ShowcaseSession.vacuumView(scene, camera);
        var underwater = ShowcaseSession.volumeView(
                scene, camera, water, bindingType.data(17L), instanceType.data(29L));

        assertSame(scene, vacuum.entryScene());
        assertSame(ViewMedium.Vacuum.INSTANCE, vacuum.medium());
        assertSame(scene, underwater.entryScene());
        ViewMedium.Volume<?, ?> medium = (ViewMedium.Volume<?, ?>) underwater.medium();
        assertSame(water, medium.implementation());
        assertEquals(17L, medium.bindingData().bits());
        assertEquals(29L, medium.instanceData().bits());
    }

    private interface Binding { }
    private interface Instance { }
}
