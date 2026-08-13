package dev.comfyfluffy.caustica.minecraft.entity;

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sampler a render type's material is read from. The mixin accessors {@code textureLocation} uses
 * are not applied in unit tests, so the sampler choice is checked against vanilla's own {@code RenderSetup}
 * through plain reflection.
 */
final class RtEntityTexturesTest {
    private static final Identifier PORTAL_TEXTURE =
            Identifier.parse("minecraft:textures/entity/end_portal/end_portal.png");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Both end-portal pipelines bind the end-sky backdrop as {@code Sampler0}, so reading the material from
     * there classifies the portal as sky and loses its registered surface implementation.
     */
    @Test
    void endPortalRenderTypesResolveTheirPortalTextureRatherThanTheEndSkyBackdrop() {
        assertEquals(PORTAL_TEXTURE, boundTexture(RenderTypes.endPortal()));
        assertEquals(PORTAL_TEXTURE, boundTexture(RenderTypes.endGateway()));
    }

    @Test
    void perTypeEntityTexturesStillComeFromSampler0() {
        Identifier zombie = Identifier.parse("minecraft:textures/entity/zombie/zombie.png");
        assertEquals("Sampler0", materialSampler(RenderTypes.entitySolid(zombie)));
        assertEquals(zombie, boundTexture(RenderTypes.entitySolid(zombie)));
    }

    private static Identifier boundTexture(RenderType renderType) {
        try {
            Field stateField = RenderType.class.getDeclaredField("state");
            assertTrue(stateField.trySetAccessible(), "RenderType.state must be readable");
            RenderSetup setup = (RenderSetup) stateField.get(renderType);
            Field texturesField = RenderSetup.class.getDeclaredField("textures");
            assertTrue(texturesField.trySetAccessible(), "RenderSetup.textures must be readable");
            Object binding = ((Map<?, ?>) texturesField.get(setup)).get(materialSampler(renderType));
            Method location = binding.getClass().getMethod("location");
            assertTrue(location.trySetAccessible(), "TextureBinding.location() must be callable");
            return (Identifier) location.invoke(binding);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("failed to read " + renderType + "'s texture bindings", exception);
        }
    }

    private static String materialSampler(RenderType renderType) {
        try {
            Method method = RtEntityTextures.class.getDeclaredMethod("materialSampler", RenderType.class);
            assertTrue(method.trySetAccessible(), "materialSampler must be accessible to the test");
            return (String) method.invoke(null, renderType);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("failed to resolve the material sampler", exception);
        }
    }
}
