package dev.comfyfluffy.caustica.minecraft.client.program;

import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.api.pass.*;
import dev.comfyfluffy.caustica.api.program.*;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelector;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelectionInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureState;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.api.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.client.material.*;
import dev.comfyfluffy.caustica.minecraft.client.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityGeometry;
import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftVulkanEntityUploader;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftProgramResources;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.MinecraftSkyCatalog;
import dev.comfyfluffy.caustica.minecraft.client.terrain.MinecraftTerrainSession;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.entity.*;
import dev.comfyfluffy.caustica.settings.*;

import java.util.ArrayList;
import java.util.List;

/** Owns replaceable Minecraft programs, material epochs, and retained world producers for one session. */
public final class MinecraftProgramSession implements MinecraftWorldSessionContribution {
    private static final ShaderSource SHADERS = ShaderSource.classpath(
            MinecraftProgramSession.class, "/caustica/shaders/minecraft", "surface", "sky");
    private static final MinecraftSkyCatalog SKIES = new MinecraftSkyCatalog();

    private final MinecraftWorldSessionContext context;
    private final MinecraftProgramResources resources;
    private final MinecraftMaterialEpochCompiler materialEpochs;
    private final MinecraftFrameSelectionInstaller frameSelections;
    private final MinecraftFrameCaptureState frames;
    private final MinecraftFrameCaptureInstaller.Lease frameCapture;
    private final MinecraftLightProvider lights;
    private final PassRegistration lightRegistration;
    private final PassRegistration overlayRegistration;
    private final dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding entityCapture;
    private final dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures entityTextures;
    private final RtTerrain terrain;
    private final OptionLookup options;
    private Pending pending;
    private Active active;
    private boolean stopped;

    private MinecraftProgramSession(MinecraftWorldSessionContext context, MinecraftProgramResources resources,
                                    MinecraftMaterialEpochCompiler materialEpochs,
                                    MinecraftFrameSelectionInstaller frameSelections,
                                    MinecraftFrameCaptureState frames,
                                    MinecraftFrameCaptureInstaller.Lease frameCapture,
                                    MinecraftLightProvider lights, PassRegistration lightRegistration,
                                    PassRegistration overlayRegistration,
                                    dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding entityCapture,
                                    dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures entityTextures,
                                    RtTerrain terrain, OptionLookup options) {
        this.context = context;
        this.resources = resources;
        this.materialEpochs = materialEpochs;
        this.frameSelections = frameSelections;
        this.frames = frames;
        this.frameCapture = frameCapture;
        this.lights = lights;
        this.lightRegistration = lightRegistration;
        this.overlayRegistration = overlayRegistration;
        this.entityCapture = entityCapture;
        this.entityTextures = entityTextures;
        this.terrain = terrain;
        this.options = options;
    }

    public static MinecraftProgramSession open(MinecraftWorldSessionContext context,
                                               MinecraftFrameSelectionInstaller frameSelections,
                                               MinecraftFrameCaptureInstaller frameCaptures,
                                               MinecraftMaterialEpochCompiler materialEpochs,
                                               MinecraftLightingCalibration calibration,
                                               dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding entityCapture,
                                               dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures entityTextures,
                                               dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities entities,
                                               RtTerrain terrain, OptionLookup options,
                                               MinecraftTelemetry.Instrumentation instrumentation) {
        java.util.Objects.requireNonNull(frameSelections, "frameSelections");
        java.util.Objects.requireNonNull(frameCaptures, "frameCaptures");
        java.util.Objects.requireNonNull(materialEpochs, "materialEpochs");
        java.util.Objects.requireNonNull(calibration, "calibration");
        java.util.Objects.requireNonNull(terrain, "terrain");
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(instrumentation, "instrumentation");
        MinecraftProgramResources resources = new MinecraftProgramResources(
                context.renderSession().gpu(), context.renderSession().resources());
        MinecraftFrameCaptureState frames = new MinecraftFrameCaptureState();
        MinecraftFrameCaptureInstaller.Lease frameCapture = null;
        MinecraftLightProvider lights = null;
        PassRegistration lightRegistration = null;
        PassRegistration overlayRegistration = null;
        try {
            frameCapture = java.util.Objects.requireNonNull(frameCaptures.install(frames, calibration),
                    "frame capture lease");
            lights = new MinecraftLightProvider(context.renderSession().lights(), context.scene(),
                    () -> celestialSettings(options.snapshot().options(MinecraftProvidersExtension.ID)),
                    frames::lightFrame);
            MinecraftLightProvider installedLights = lights;
            lightRegistration = context.renderSession().passes().addWorldResourcePass(
                    setup -> new LightUpdatePass(installedLights, instrumentation));
            overlayRegistration = context.renderSession().passes().addUiPass(
                    WorldOverlayPass.ID, setup -> new WorldOverlayPass(setup, entities, terrain));
            MinecraftProgramSession session = new MinecraftProgramSession(
                    context, resources, materialEpochs, frameSelections, frames, frameCapture,
                    lights, lightRegistration, overlayRegistration, entityCapture, entityTextures, terrain, options);
            session.beginReplacement(context.resourcePackEpoch());
            return session;
        } catch (RuntimeException | Error failure) {
            if (overlayRegistration != null) overlayRegistration.close();
            if (lightRegistration != null) lightRegistration.close();
            if (lights != null) lights.close();
            if (frameCapture != null) frameCapture.close();
            resources.close();
            throw failure;
        }
    }

    @Override public synchronized void resourcePackChanged(ResourcePackEpoch epoch) {
        entityTextures.reset();
        if (!stopped) beginReplacement(epoch);
    }

    private synchronized void beginReplacement(ResourcePackEpoch resourcePack) {
        MinecraftMaterialLookup lookup;
        try {
            lookup = materialEpochs.compile(resourcePack, List.of());
        } catch (RuntimeException | Error failure) {
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not be compiled",
                    resourcePack.generation(), failure);
            return;
        }
        Pending request = new Pending(resourcePack.generation(), lookup);
        MinecraftProgramResources.PreparedUpload[] prepared = new MinecraftProgramResources.PreparedUpload[1];
        try {
            request.upload = context.renderSession().passes().addWorldResourcePass(
                    setup -> {
                        prepared[0] = resources.prepareUpload(lookup,
                                () -> uploadSubmitted(request), () -> uploadCompleted(request));
                        return prepared[0].pass();
                    });
            request.prepared = java.util.Objects.requireNonNull(prepared[0], "upload factory result").epoch();
        } catch (RuntimeException | Error failure) {
            if (request.prepared == null && prepared[0] != null) {
                request.prepared = prepared[0].epoch();
            }
            request.close();
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not be prepared",
                    resourcePack.generation(), failure);
            return;
        }
        Pending displaced = pending;
        pending = request;
        if (displaced != null) displaced.close();
    }

    private synchronized void uploadSubmitted(Pending request) {
        if (pending != request || stopped) return;
        try {
            resources.populateMaterialRecords(request.prepared.gpu(), request.lookup);
        } catch (RuntimeException | Error failure) {
            pending = null;
            request.close();
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not populate its resources",
                    request.generation, failure);
        }
    }

    private synchronized void uploadCompleted(Pending request) {
        if (request.upload != null) {
            request.upload.close();
            request.upload = null;
        }
        if (pending != request || stopped) return;
        try {
            resources.seal(request.prepared.gpu());
            request.registration = registerPrograms(
                    context.renderSession().program(), roots(request.prepared.gpu()));
            request.registration.whenComplete(completion -> completed(request, completion));
        } catch (RuntimeException | Error failure) {
            pending = null;
            request.close();
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not publish its resources",
                    request.generation, failure);
        }
    }

    private synchronized void completed(Pending request, ProgramRegistration.Completion completion) {
        if (pending != request || stopped) return;
        if (!(completion instanceof ProgramRegistration.Ready)) {
            pending = null;
            request.close();
            if (completion instanceof ProgramRegistration.Failed failed) {
                CausticaMod.LOGGER.error("Minecraft program epoch {} failed: {}",
                        request.generation, failed.failure().diagnostics());
            }
            return;
        }
        try {
            activate(request);
        } catch (RuntimeException | Error failure) {
            pending = null;
            request.close();
            CausticaMod.LOGGER.error("Minecraft program epoch {} could not bind its producers",
                    request.generation, failure);
        }
    }

    private void activate(Pending request) {
        ProgramRegistration<MinecraftPrograms> registration = request.registration;
        MinecraftPrograms programs = registration.exports();
        PassRegistration sky = createSky(programs, request.generation);
        Active displaced = active;
        if (displaced != null) displaced.stopSceneProducers();

        MinecraftTerrainSession terrainSession = new MinecraftTerrainSession(
                context.renderSession().gpu(), context.renderSession().resources(), terrain, entityTextures);
        MinecraftFrameSelectionInstaller.Lease frameSelection = null;
        MinecraftEntityGeometry entityGeometry = null;
        dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding.Lease entityLease = null;
        MinecraftFrameSelector frameSelector = null;
        try {
            terrainSession.bind(programs, context.renderSession().geometry(),
                    context.renderSession().lights(), context.scene());
            terrainSession.publishMaterialLookup(request.lookup);
            frameSelector = new MinecraftFrameSelector(context.scene(), programs.waterVolume(),
                    request.prepared.gpu().fallbackBindingData(),
                    request.prepared.gpu().fallbackInstanceData());
            frameSelection = java.util.Objects.requireNonNull(frameSelections.install(frameSelector),
                    "frame selection lease");
            entityGeometry = new MinecraftEntityGeometry(context.renderSession().geometry(), context.scene(),
                    new MinecraftVulkanEntityUploader(context.renderSession().gpu(), request.lookup,
                            programs, entityTextures, context.renderSession().resources()));
            entityLease = java.util.Objects.requireNonNull(entityCapture.install(entityGeometry),
                    "entity capture lease");
        } catch (RuntimeException | Error failure) {
            if (entityLease != null) entityLease.close();
            if (entityGeometry != null) { entityGeometry.stop(); entityGeometry.close(); }
            if (frameSelection != null) frameSelection.close();
            terrainSession.stop();
            if (sky != null) sky.close();
            throw failure;
        }

        ArrayList<RetiredPrograms> delayed = new ArrayList<>();
        if (displaced != null) {
            delayed.addAll(displaced.delayed);
            delayed.add(new RetiredPrograms(displaced.sky, displaced.registration, displaced.epoch));
        }
        Active replacement = new Active(request.generation, registration, request.prepared.gpu(), terrainSession,
                frameSelector, frameSelection, entityGeometry, entityLease, sky, delayed);
        request.registration = null;
        request.prepared = null;
        pending = null;
        active = replacement;
        if (sky == null) replacement.releaseDisplacedPrograms();
    }

    private PassRegistration createSky(MinecraftPrograms programs, long generation) {
        if (!SKIES.supports(context.dimension())) return null;
        return context.renderSession().passes().addWorldResourcePass(setup -> {
            SkyLutPass sky = SKIES.create(context.dimension(), setup.gpu(), this::options,
                    frames::skyFrame, programs.environment(), context.environment(),
                    context.renderSession().resources(), generation);
            return new FirstRecordPass(sky, () -> skySelected(generation));
        });
    }

    private synchronized void skySelected(long generation) {
        if (active != null && active.generation == generation) active.releaseDisplacedPrograms();
    }

    private static Roots roots(MinecraftProgramResources.Epoch epoch) {
        return new Roots(epoch.implementationData(), epoch.fallbackBindingData(),
                epoch.fallbackInstanceData());
    }

    static ProgramRegistration<MinecraftPrograms> registerPrograms(ProgramChannel channel, Roots roots) {
        return channel.register(builder -> {
            var coverage = SHADERS.definition("caustica_minecraft_coverage", "MinecraftCoverage");
            var material = new SurfaceDefinition<>(
                    SHADERS.definition("caustica_minecraft_surface", "MinecraftSurface"),
                    coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                    MinecraftProgramTypes.INSTANCE_DATA);
            return new MinecraftPrograms(builder.surface(material),
                    builder.surface(SurfaceDefinition.of(
                            SHADERS.definition("caustica_water_surface", "WaterSurface"),
                            coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.surface(SurfaceDefinition.of(
                            SHADERS.definition("caustica_portal_surface", "PortalSurface"),
                            coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.volume(VolumeDefinition.of(
                            SHADERS.definition("caustica_water_surface", "WaterVolume"),
                            roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.environment(new EnvironmentDefinition<>(
                            SHADERS.definition("caustica_minecraft_overworld_sky", "MinecraftOverworldSky"),
                            MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA)));
        });
    }

    private OptionValues options() {
        return options.snapshot().options(MinecraftProvidersExtension.ID);
    }

    static MinecraftLightProvider.CelestialSettings celestialSettings(OptionValues values) {
        return new MinecraftLightProvider.CelestialSettings(
                values.get(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES),
                values.get(SkyLutPass.SUN_ANGULAR_RADIUS_DEGREES),
                values.get(SkyLutPass.MOON_ANGULAR_RADIUS_DEGREES));
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        frameCapture.close();
        overlayRegistration.close();
        lightRegistration.close();
        if (active != null && active.sky != null) active.sky.close();
        if (active != null) active.stopSceneProducers();
        lights.close();
        if (pending != null) pending.close();
        pending = null;
        if (active != null) active.closePrograms();
        active = null;
        terrain.shutdown();
    }

    @Override public void close() { resources.close(); }

    record Roots(ShaderData<MinecraftProgramTypes.ImplementationData> implementation,
                 ShaderData<MinecraftProgramTypes.PrimitiveData> fallbackBinding,
                 ShaderData<MinecraftProgramTypes.InstanceData> fallbackInstance) {
        Roots {
            java.util.Objects.requireNonNull(implementation, "implementation");
            java.util.Objects.requireNonNull(fallbackBinding, "fallbackBinding");
            java.util.Objects.requireNonNull(fallbackInstance, "fallbackInstance");
            if (implementation.bits() == 0L || fallbackBinding.bits() == 0L || fallbackInstance.bits() == 0L) {
                throw new IllegalArgumentException("Minecraft program roots must be nonzero");
            }
        }
    }

    private static final class Pending {
        final long generation;
        final MinecraftMaterialLookup lookup;
        PassRegistration upload;
        ProgramRegistration<MinecraftPrograms> registration;
        MinecraftProgramResources.PreparedEpoch prepared;
        Pending(long generation, MinecraftMaterialLookup lookup) {
            this.generation = generation;
            this.lookup = lookup;
        }
        void close() {
            if (upload != null) upload.close();
            if (registration != null) registration.close();
            if (prepared != null) prepared.gpu().close();
            upload = null;
            registration = null;
            prepared = null;
        }
    }

    private final class Active {
        final long generation;
        final ProgramRegistration<MinecraftPrograms> registration;
        final MinecraftProgramResources.Epoch epoch;
        final MinecraftTerrainSession terrain;
        final MinecraftFrameSelector frameSelector;
        final MinecraftFrameSelectionInstaller.Lease frameSelection;
        final MinecraftEntityGeometry entityGeometry;
        final dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding.Lease entityLease;
        final PassRegistration sky;
        final ArrayList<RetiredPrograms> delayed;
        boolean producersStopped;
        Active(long generation, ProgramRegistration<MinecraftPrograms> registration,
               MinecraftProgramResources.Epoch epoch,
               MinecraftTerrainSession terrain, MinecraftFrameSelector frameSelector,
               MinecraftFrameSelectionInstaller.Lease frameSelection,
               MinecraftEntityGeometry entityGeometry,
               dev.comfyfluffy.caustica.minecraft.rendering.MinecraftEntityCaptureBinding.Lease entityLease,
               PassRegistration sky, ArrayList<RetiredPrograms> delayed) {
            this.generation = generation;
            this.registration = registration;
            this.epoch = epoch;
            this.terrain = terrain;
            this.frameSelector = frameSelector;
            this.frameSelection = frameSelection;
            this.entityGeometry = entityGeometry;
            this.entityLease = entityLease;
            this.sky = sky;
            this.delayed = delayed;
        }
        void stopSceneProducers() {
            if (producersStopped) return;
            producersStopped = true;
            frameSelection.close();
            entityLease.close();
            entityGeometry.stop();
            entityGeometry.close();
            terrain.stop();
        }
        void releaseDisplacedPrograms() {
            delayed.forEach(RetiredPrograms::close);
            delayed.clear();
        }
        void closePrograms() {
            if (sky != null) sky.close();
            releaseDisplacedPrograms();
            registration.close();
            epoch.close();
        }
    }

    private record RetiredPrograms(PassRegistration sky, ProgramRegistration<MinecraftPrograms> registration,
                                   MinecraftProgramResources.Epoch epoch) {
        void close() {
            if (sky != null) sky.close();
            registration.close();
            epoch.close();
        }
    }

    static final class LightUpdatePass implements Pass<PassFrame> {
        private final MinecraftLightProvider lights;
        private final MinecraftTelemetry.Instrumentation instrumentation;
        LightUpdatePass(MinecraftLightProvider lights, MinecraftTelemetry.Instrumentation instrumentation) {
            this.lights = lights;
            this.instrumentation = instrumentation;
        }
        @Override public void record(PassFrame frame) {
            long started = instrumentation.startStage();
            try {
                lights.update();
            } finally {
                instrumentation.endStage("terrain.lightScenePublish", started);
            }
        }
        @Override public void close() { }
    }

    static final class FirstRecordPass implements Pass<PassFrame> {
        private final Pass<PassFrame> delegate;
        private final Runnable firstRecord;
        private boolean recorded;
        FirstRecordPass(Pass<PassFrame> delegate, Runnable firstRecord) {
            this.delegate = delegate;
            this.firstRecord = firstRecord;
        }
        @Override public void record(PassFrame frame) {
            delegate.record(frame);
            if (!recorded) {
                recorded = true;
                firstRecord.run();
            }
        }
        @Override public void close() { delegate.close(); }
    }
}
