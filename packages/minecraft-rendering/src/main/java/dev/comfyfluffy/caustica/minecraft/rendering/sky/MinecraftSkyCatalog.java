package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;

import java.util.Map;
import java.util.function.Supplier;

/** Minecraft-owned dimension catalog for built-in environment renderers. */
public final class MinecraftSkyCatalog {
    private static final ResourceId OVERWORLD = ResourceId.of("minecraft", "overworld");
    private final Map<ResourceId, Factory> factories;

    public MinecraftSkyCatalog() {
        factories = Map.of(OVERWORLD, SkyLutPass::new);
    }

    public boolean supports(MinecraftDimensionKey dimension) {
        return factories.containsKey(dimension.id());
    }

    public SkyLutPass create(MinecraftDimensionKey dimension, GpuDevice gpu,
                             Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames,
                             EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                             MinecraftEnvironmentSelector selector, ResourceFactory resources,
                             long resourcePackEpoch) {
        Factory factory = factories.get(dimension.id());
        return factory == null ? null : factory.create(gpu, options, frames, environment, selector,
                resources, resourcePackEpoch);
    }

    @FunctionalInterface
    private interface Factory {
        SkyLutPass create(GpuDevice gpu, Supplier<OptionValues> options,
                          Supplier<MinecraftSkyFrame> frames,
                          EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                          MinecraftEnvironmentSelector selector, ResourceFactory resources,
                          long resourcePackEpoch);
    }
}
