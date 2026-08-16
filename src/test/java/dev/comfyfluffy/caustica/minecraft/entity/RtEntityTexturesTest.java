package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sampler a render type's material is read from. The mixin accessors {@code textureLocation} uses
 * are not applied in unit tests, so the sampler choice is checked against vanilla's own {@code RenderSetup}
 * through plain reflection.
 */
final class RtEntityTexturesTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void endPortalRenderTypesUseTheTexturelessProceduralMaterial() {
        for (RenderType renderType : new RenderType[]{RenderTypes.endPortal(), RenderTypes.endGateway()}) {
            SceneMesh.NamedMaterial material = assertInstanceOf(SceneMesh.NamedMaterial.class,
                    standaloneMaterial(renderType));
            assertEquals(MinecraftMaterialSource.END_PORTAL, material.material().id());
            assertNull(material.texture());
        }
    }

    @Test
    void perTypeEntityTexturesStillComeFromSampler0() {
        Identifier zombie = Identifier.parse("minecraft:textures/entity/zombie/zombie.png");
        assertEquals(zombie, boundTexture(RenderTypes.entitySolid(zombie)));
    }

    private static Identifier boundTexture(RenderType renderType) {
        try {
            Field stateField = RenderType.class.getDeclaredField("state");
            assertTrue(stateField.trySetAccessible(), "RenderType.state must be readable");
            RenderSetup setup = (RenderSetup) stateField.get(renderType);
            Field texturesField = RenderSetup.class.getDeclaredField("textures");
            assertTrue(texturesField.trySetAccessible(), "RenderSetup.textures must be readable");
            Object binding = ((Map<?, ?>) texturesField.get(setup)).get("Sampler0");
            var location = binding.getClass().getMethod("location");
            assertTrue(location.trySetAccessible(), "TextureBinding.location() must be callable");
            return (Identifier) location.invoke(binding);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("failed to read " + renderType + "'s texture bindings", exception);
        }
    }

    private static SceneMesh.MaterialReference standaloneMaterial(RenderType renderType) {
        try {
            var method = RtEntityCollectorBase.class.getDeclaredMethod("standaloneMaterial", RenderType.class);
            assertTrue(method.trySetAccessible(), "standaloneMaterial must be accessible to the test");
            return (SceneMesh.MaterialReference) method.invoke(null, renderType);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("failed to resolve the entity material", exception);
        }
    }
}
