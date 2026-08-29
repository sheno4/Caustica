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

    public MinecraftProvidersExtension(MinecraftFrameSelectionInstaller frameSelections,
                                       MinecraftFrameCaptureInstaller frameCaptures,
                                       MinecraftMaterialEpochCompiler materialEpochs,
                                       MinecraftLightingCalibration calibration) {
        this.frameSelections = java.util.Objects.requireNonNull(frameSelections, "frameSelections");
        this.frameCaptures = java.util.Objects.requireNonNull(frameCaptures, "frameCaptures");
        this.materialEpochs = java.util.Objects.requireNonNull(materialEpochs, "materialEpochs");
        this.calibration = java.util.Objects.requireNonNull(calibration, "calibration");
    }

    @Override public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(context -> MinecraftProgramSession.open(
                context, frameSelections, frameCaptures, materialEpochs, calibration));
    }

    @Override public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.literal("Minecraft"))
                .group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS).register();
    }
}
