package dev.comfyfluffy.caustica.api.retained;
import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.api.light.*;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
final class RetainedChannelContractTest {
    @Test void editsAcceptReadyReferencesAndPublishDirectly() throws ReflectiveOperationException {
        assertSame(void.class,SceneChannel.class.getMethod("edit",List.class).getReturnType());
        assertSame(ReadyMesh.class,SceneEdit.SetInstance.class.getMethod("mesh").getReturnType());
        assertSame(InstanceId.class,SceneEdit.SetInstance.class.getMethod("instance").getReturnType());
        assertSame(LightId.class,SceneEdit.SetLight.class.getMethod("light").getReturnType());
        assertSame(CompletableFuture.class,MeshPreparer.class.getMethod("prepare",ShaderDataType.class,MeshBuild.class).getReturnType());
    }
}
