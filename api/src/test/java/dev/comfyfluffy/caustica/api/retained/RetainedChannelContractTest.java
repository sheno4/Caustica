package dev.comfyfluffy.caustica.api.retained;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RetainedChannelContractTest {
    @Test
    void eachSubmitAcceptsExactlyOneBatch() throws ReflectiveOperationException {
        assertSame(void.class, GeometryChannel.class.getMethod("submit", RetainedBatch.class).getReturnType());
        assertSame(void.class, LightChannel.class.getMethod("submit", RetainedBatch.class).getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> GeometryChannel.class.getMethod("submit", java.util.List.class));
        assertThrows(NoSuchMethodException.class,
                () -> LightChannel.class.getMethod("submit", java.util.List.class));
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
}
