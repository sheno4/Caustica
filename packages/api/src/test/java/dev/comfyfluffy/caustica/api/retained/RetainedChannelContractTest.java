package dev.comfyfluffy.caustica.api.retained;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RetainedChannelContractTest {
    @Test
    void geometrySupportsAtomicIndependentRetirementGroupsWhileLightsStaySingleBatch()
            throws ReflectiveOperationException {
        assertSame(dev.comfyfluffy.caustica.api.geometry.GeometryPublication.class,
                GeometryChannel.class.getMethod("submit", RetainedBatch.class).getReturnType());
        var submitGroup = GeometryChannel.class.getMethod("submitGroup", List.class);
        assertSame(dev.comfyfluffy.caustica.api.geometry.GeometryPublication.class,
                submitGroup.getReturnType());
        assertTrue(Modifier.isAbstract(submitGroup.getModifiers()));
        assertSame(void.class, LightChannel.class.getMethod("submit", RetainedBatch.class).getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> LightChannel.class.getMethod("submitGroup", List.class));
    }

    @Test
    void instanceUsesInstanceDataVocabulary() throws ReflectiveOperationException {
        assertSame(dev.comfyfluffy.caustica.api.program.ShaderData.class,
                GeometryChannel.SetInstance.class.getMethod("instanceData").getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> GeometryChannel.SetInstance.class.getMethod("properties"));
    }

    @Test
    void meshIdentityReceivesItsRuntimeInstanceSchema() throws ReflectiveOperationException {
        assertSame(dev.comfyfluffy.caustica.api.geometry.MeshId.class,
                GeometryChannel.class.getMethod("newMesh",
                        dev.comfyfluffy.caustica.api.program.ShaderDataType.class).getReturnType());
        assertThrows(NoSuchMethodException.class, () -> GeometryChannel.class.getMethod("newMesh"));
    }

    @Test
    void mutationOperationsKeepOwnerLocalIdentityTypes() throws ReflectiveOperationException {
        assertSame(MeshId.class, GeometryChannel.SetMesh.class.getMethod("mesh").getReturnType());
        assertSame(InstanceId.class,
                GeometryChannel.SetInstance.class.getMethod("instance").getReturnType());
        assertSame(LightId.class, LightChannel.SetLight.class.getMethod("light").getReturnType());
    }

    @Test
    void retainedValuesUseNonOwningSelectionReferenceTypes() throws ReflectiveOperationException {
        assertSame(SceneId.class,
                GeometryChannel.SetInstance.class.getMethod("scene").getReturnType());
        assertSame(SceneId.class, LightChannel.SetLight.class.getMethod("scene").getReturnType());
        assertSame(SurfaceId.class, MeshBuild.SurfaceSlot.class.getMethod("surface").getReturnType());
        assertSame(VolumeId.class, MeshBuild.VolumeSlot.class.getMethod("volume").getReturnType());
    }
}
