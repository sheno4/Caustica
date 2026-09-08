package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftFrameAdapter;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftRuntimeHost;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftDeviceBringup;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientServiceOwnershipTest {
    @Test
    void processServicesDoNotPublishMutableStaticState() {
        List<Class<?>> services = List.of(MinecraftFrameAdapter.class, MinecraftRuntimeHost.class,
                VanillaRenderController.class, WorldRenderComposite.class,
                MinecraftDeviceBringup.class, MinecraftVulkanBackend.class);

        for (Class<?> service : services) {
            for (var field : service.getDeclaredFields()) {
                assertTrue(!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()),
                        () -> service.getSimpleName() + "." + field.getName() + " is mutable static state");
            }
        }
    }
}
