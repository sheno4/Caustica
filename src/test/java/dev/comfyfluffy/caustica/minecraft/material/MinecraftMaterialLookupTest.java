package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MinecraftMaterialLookupTest {
    @Test
    void resourcePathIsConvertedToLogicalMaterialName() {
        assertEquals(ResourceId.of("pack", "actor/sample"), MinecraftMaterialLookup.logicalTexture(
                Identifier.fromNamespaceAndPath("pack", "textures/actor/sample.png")));
        assertEquals(ResourceId.of("pack", "already/logical"), MinecraftMaterialLookup.logicalTexture(
                Identifier.fromNamespaceAndPath("pack", "already/logical")));
        assertNull(MinecraftMaterialLookup.logicalTexture(null));
    }
}
