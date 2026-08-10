package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialClassifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftMaterialSourceTest {
    @Test
    void namedWaterMaterialSelectsTheMinecraftProceduralSurface() {
        MaterialDefinition material = MinecraftMaterialSource.waterDefinition();

        assertEquals(MinecraftMaterialSource.WATER, material.handle().id());
        assertEquals(1.0f, material.baseColorR());
        assertEquals(1.0f, material.baseColorG());
        assertEquals(1.0f, material.baseColorB());
        assertEquals(OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS,
                material.specularRoughness());
        assertEquals(MinecraftMaterialClassifier.WATER_IOR, material.specularIor());
        assertEquals(1.0f, material.transmissionWeight());
        assertEquals(MinecraftProvidersExtension.WATER_SURFACE, material.surface());
    }
}
