package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelectionInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.client.program.MinecraftProgramSession;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
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
    private final dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures entityTextures;
    private final dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities entities;
    private final RtTerrain terrain;
    private final MinecraftTelemetry.Instrumentation instrumentation;

    public MinecraftProvidersExtension(MinecraftFrameSelectionInstaller frameSelections,
                                       MinecraftFrameCaptureInstaller frameCaptures,
                                       MinecraftMaterialEpochCompiler materialEpochs,
                                       MinecraftLightingCalibration calibration,
                                       MinecraftEntityCaptureBinding entityCapture,
                                       dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures entityTextures,
                                       dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities entities,
                                       RtTerrain terrain, MinecraftTelemetry.Instrumentation instrumentation) {
        this.frameSelections = java.util.Objects.requireNonNull(frameSelections, "frameSelections");
        this.frameCaptures = java.util.Objects.requireNonNull(frameCaptures, "frameCaptures");
        this.materialEpochs = java.util.Objects.requireNonNull(materialEpochs, "materialEpochs");
        this.calibration = java.util.Objects.requireNonNull(calibration, "calibration");
        this.entityCapture = entityCapture;
        this.entityTextures = entityTextures;
        this.entities = entities;
        this.terrain = java.util.Objects.requireNonNull(terrain, "terrain");
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation, "instrumentation");
    }

    @Override public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(context -> MinecraftProgramSession.open(
                context, frameSelections, frameCaptures, materialEpochs, calibration,
                entityCapture, entityTextures, entities, terrain, api.options(), instrumentation));
    }

    @Override public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.literal("Minecraft"))
                .group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS).register();
    }
}
