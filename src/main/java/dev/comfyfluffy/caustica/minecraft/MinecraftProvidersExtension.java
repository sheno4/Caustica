package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.program.MinecraftProgramSession;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;

/** Installs Caustica's core Minecraft world-session contribution and settings feature. */
public final class MinecraftProvidersExtension implements MinecraftExtension, CausticaSettingsExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft");
    private final MinecraftFrameSelectionInstaller frameSelections;
    private final MinecraftFrameCaptureInstaller frameCaptures;
    private final MinecraftMaterialEpochCompiler materialEpochs;
    private final MinecraftLightingCalibration calibration;
    private final MinecraftEntityCaptureBinding entityCapture;
    private final dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures entityTextures;
    private final dev.comfyfluffy.caustica.minecraft.entity.RtEntities entities;

    public MinecraftProvidersExtension(MinecraftFrameSelectionInstaller frameSelections,
                                       MinecraftFrameCaptureInstaller frameCaptures,
                                       MinecraftMaterialEpochCompiler materialEpochs,
                                       MinecraftLightingCalibration calibration,
                                       MinecraftEntityCaptureBinding entityCapture,
                                       dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures entityTextures,
                                       dev.comfyfluffy.caustica.minecraft.entity.RtEntities entities) {
        this.frameSelections = java.util.Objects.requireNonNull(frameSelections, "frameSelections");
        this.frameCaptures = java.util.Objects.requireNonNull(frameCaptures, "frameCaptures");
        this.materialEpochs = java.util.Objects.requireNonNull(materialEpochs, "materialEpochs");
        this.calibration = java.util.Objects.requireNonNull(calibration, "calibration");
        this.entityCapture = entityCapture;
        this.entityTextures = entityTextures;
        this.entities = entities;
    }

    @Override public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(context -> MinecraftProgramSession.open(
                context, frameSelections, frameCaptures, materialEpochs, calibration,
                entityCapture, entityTextures, entities));
    }

    @Override public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.literal("Minecraft"))
                .group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS).register();
    }
}
