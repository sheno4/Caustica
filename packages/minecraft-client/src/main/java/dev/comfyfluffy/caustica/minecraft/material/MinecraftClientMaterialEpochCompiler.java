package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.List;
import java.util.Objects;

/** Captures Minecraft host resources and compiles them into one rendering-owned material epoch. */
public final class MinecraftClientMaterialEpochCompiler implements MinecraftMaterialEpochCompiler {
    private final MinecraftLightingCalibration calibration;

    public MinecraftClientMaterialEpochCompiler(MinecraftLightingCalibration calibration) {
        this.calibration = Objects.requireNonNull(calibration, "calibration");
    }

    @Override public MinecraftMaterialLookup compile(ResourcePackEpoch epoch, List<MinecraftMaterialRule> rules) {
        List<MinecraftMaterialRule> immutableRules = List.copyOf(rules);
        List<MaterialTextureResource> catalog = MinecraftMaterialCatalogBuilder.build(immutableRules, calibration);
        MinecraftMaterialPageCompiler.Result pages = MinecraftMaterialPageCompiler.compile(catalog);
        return MinecraftMaterialLookup.compile(epoch, immutableRules, catalog, pages);
    }
}
