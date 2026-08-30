package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrDefaults;
import dev.comfyfluffy.caustica.minecraft.gen.MinecraftMaterialData;

import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Immutable CPU form of one entry in the Minecraft shader material table. */
public record MinecraftMaterialRecord(int features, float maxLod,
                                      int surface0Texture, int surface1Texture,
                                      int normalTexture, int emissionTexture,
                                      MaterialUv materialUv, MaterialUv baseColorUv,
                                      Color3 baseColor, float baseMetalness,
                                      float specularRoughness, float specularIor,
                                      float transmissionWeight, float subsurfaceWeight,
                                      Color3 emissionColor, float emissionLuminanceCdM2) {
    public MinecraftMaterialRecord {
        requireTexture(surface0Texture);
        requireTexture(surface1Texture);
        requireTexture(normalTexture);
        requireTexture(emissionTexture);
        Objects.requireNonNull(materialUv, "materialUv");
        Objects.requireNonNull(baseColorUv, "baseColorUv");
        Objects.requireNonNull(baseColor, "baseColor");
        Objects.requireNonNull(emissionColor, "emissionColor");
        unit("baseMetalness", baseMetalness);
        unit("specularRoughness", specularRoughness);
        positive("specularIor", specularIor);
        unit("transmissionWeight", transmissionWeight);
        unit("subsurfaceWeight", subsurfaceWeight);
        if (!Float.isFinite(maxLod) || maxLod < 0.0f) throw new IllegalArgumentException("maxLod must be non-negative");
        if (!Float.isFinite(emissionLuminanceCdM2) || emissionLuminanceCdM2 < 0.0f) {
            throw new IllegalArgumentException("emission luminance must be non-negative");
        }
    }

    static MinecraftMaterialRecord fallback() {
        return from(new MinecraftMaterialPageCompiler.CompiledMaterial(0, 0, 0, 0, 0, 0,
                MaterialUv.IDENTITY, MaterialUv.IDENTITY), Color3.WHITE, 0.0f, 1.0f,
                OpenPbrDefaults.SPECULAR_IOR, 0.0f, 0.0f, Color3.WHITE, 0.0f);
    }

    static MinecraftMaterialRecord waterBoundary() {
        return from(new MinecraftMaterialPageCompiler.CompiledMaterial(0, 0, 0, 0, 0, 0,
                MaterialUv.IDENTITY, MaterialUv.IDENTITY), Color3.WHITE, 0.0f,
                OpenPbrDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS,
                OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR, 1.0f, 0.0f,
                Color3.WHITE, 0.0f);
    }

    static MinecraftMaterialRecord from(MinecraftMaterialPageCompiler.CompiledMaterial page,
                                        Color3 baseColor, float metalness, float roughness, float ior,
                                        float transmission, float subsurface, Color3 emissionColor,
                                        float emissionLuminance) {
        return new MinecraftMaterialRecord(page.features(), page.maxLod(), page.surface0Texture(),
                page.surface1Texture(), page.normalTexture(), page.emissionTexture(), page.materialUv(),
                page.baseColorUv(), baseColor, metalness, roughness, ior, transmission, subsurface,
                emissionColor, emissionLuminance);
    }

    MinecraftMaterialData shaderData(IntUnaryOperator descriptorIndex) {
        var material = new MinecraftMaterialData.Float4(materialUv.u(), materialUv.v(),
                materialUv.inverseDu(), materialUv.inverseDv());
        var albedo = new MinecraftMaterialData.Float4(baseColorUv.u(), baseColorUv.v(),
                baseColorUv.inverseDu(), baseColorUv.inverseDv());
        return new MinecraftMaterialData(features, maxLod,
                texture(descriptorIndex, surface0Texture), texture(descriptorIndex, surface1Texture),
                texture(descriptorIndex, normalTexture), texture(descriptorIndex, emissionTexture),
                material, albedo, baseColor.shader(), baseMetalness, specularRoughness, specularIor,
                transmissionWeight, subsurfaceWeight, emissionColor.shader(), emissionLuminanceCdM2);
    }

    private static MinecraftMaterialData.SampledTexture2DIndex texture(IntUnaryOperator indices, int ordinal) {
        return new MinecraftMaterialData.SampledTexture2DIndex(indices.applyAsInt(ordinal));
    }
    private static void requireTexture(int value) {
        if (value < 0) throw new IllegalArgumentException("texture ordinal must be non-negative");
    }
    private static void positive(String name, float value) {
        if (!Float.isFinite(value) || value <= 0.0f) throw new IllegalArgumentException(name + " must be positive");
    }
    private static void unit(String name, float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }

    public record Color3(float r, float g, float b) {
        public static final Color3 WHITE = new Color3(1.0f, 1.0f, 1.0f);
        public Color3 {
            if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b)
                    || r < 0.0f || g < 0.0f || b < 0.0f) {
                throw new IllegalArgumentException("material colors must be finite and non-negative");
            }
        }
        private MinecraftMaterialData.Float3 shader() { return new MinecraftMaterialData.Float3(r, g, b); }
    }
}
