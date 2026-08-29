package dev.comfyfluffy.caustica.minecraft.program;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.pass.*;
import dev.comfyfluffy.caustica.api.program.*;
import dev.comfyfluffy.caustica.minecraft.MinecraftFrameSelector;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.api.*;
import dev.comfyfluffy.caustica.minecraft.material.*;
import dev.comfyfluffy.caustica.minecraft.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.terrain.MinecraftTerrainSession;
import dev.comfyfluffy.caustica.settings.*;

import java.util.ArrayList;
import java.util.List;

/** Owns replaceable Minecraft programs, material epochs, and retained world producers for one session. */
public final class MinecraftProgramSession implements MinecraftWorldSessionContribution {
    private static final ShaderSource SHADERS = ShaderSource.classpath(
            MinecraftProgramSession.class, "/caustica/shaders/minecraft", "surface", "sky");
    private static final ResourceId OVERWORLD = ResourceId.of("minecraft", "overworld");

    private final MinecraftWorldSessionContext context;
    private final MinecraftProgramResources resources;
    private final MinecraftLightProvider lights;
    private final PassRegistration lightRegistration;
    private final PassRegistration overlayRegistration;
    private Pending pending;
    private Active active;
    private boolean stopped;

    private MinecraftProgramSession(MinecraftWorldSessionContext context, MinecraftProgramResources resources,
                                    MinecraftLightProvider lights, PassRegistration lightRegistration,
                                    PassRegistration overlayRegistration) {
        this.context = context;
        this.resources = resources;
        this.lights = lights;
        this.lightRegistration = lightRegistration;
        this.overlayRegistration = overlayRegistration;
    }

    public static MinecraftProgramSession open(MinecraftWorldSessionContext context) {
        MinecraftProgramResources resources = new MinecraftProgramResources(context.renderSession().gpu());
        MinecraftLightProvider lights = null;
        PassRegistration lightRegistration = null;
        PassRegistration overlayRegistration = null;
        try {
            lights = new MinecraftLightProvider(context.renderSession().lights(), context.scene(),
                    MinecraftProgramSession::celestialSettings);
            MinecraftLightProvider installedLights = lights;
            lightRegistration = context.renderSession().passes().addWorldResourcePass(
                    setup -> new LightUpdatePass(installedLights));
            overlayRegistration = context.renderSession().passes().addUiPass(WorldOverlayPass::new);
            MinecraftProgramSession session = new MinecraftProgramSession(
                    context, resources, lights, lightRegistration, overlayRegistration);
            session.beginReplacement(context.resourcePackEpoch());
            return session;
        } catch (RuntimeException | Error failure) {
            if (overlayRegistration != null) overlayRegistration.close();
            if (lightRegistration != null) lightRegistration.close();
            if (lights != null) lights.close();
            resources.close();
            throw failure;
        }
    }

    @Override public synchronized void resourcePackChanged(ResourcePackEpoch epoch) {
        if (!stopped) beginReplacement(epoch);
    }

    private void beginReplacement(ResourcePackEpoch resourcePack) {
        MinecraftMaterialLookup lookup;
        try {
            lookup = MinecraftProgramResources.compileCurrent(resourcePack, List.of());
        } catch (RuntimeException | Error failure) {
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not be compiled",
                    resourcePack.generation(), failure);
            return;
        }
        Pending request = new Pending(resourcePack.generation(), lookup);
        try {
            request.upload = context.renderSession().passes().addWorldResourcePass(
                    setup -> resources.createUploadPass(lookup, published -> uploaded(request, published)));
        } catch (RuntimeException | Error failure) {
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not start uploading",
                    resourcePack.generation(), failure);
            return;
        }
        Pending displaced = pending;
        pending = request;
        if (displaced != null) displaced.close();
    }

    private void uploaded(Pending request, MinecraftProgramResources.PublishedEpoch published) {
        ProgramRegistration<MinecraftPrograms> registration;
        try {
            registration = registerPrograms(context.renderSession().program(), roots(published.gpu()));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                if (pending == request) pending = null;
            }
            CausticaMod.LOGGER.error("Minecraft program epoch {} could not be registered",
                    request.generation, failure);
            throw failure;
        }
        synchronized (this) {
            request.upload.close();
            request.upload = null;
            request.registration = registration;
            request.published = published;
            if (stopped || pending != request) {
                request.close();
                return;
            }
            registration.readiness().whenComplete(completion -> completed(request, completion));
        }
    }

    private synchronized void completed(Pending request, ProgramTicket.Completion completion) {
        if (pending != request || stopped) return;
        if (!(completion instanceof ProgramTicket.Ready)) {
            pending = null;
            request.close();
            if (completion instanceof ProgramTicket.Failed failed) {
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

        MinecraftTerrainSession terrain = new MinecraftTerrainSession(context.renderSession().gpu());
        MinecraftFrameSelector.Lease frameSelection = null;
        try {
            terrain.bind(programs, context.renderSession().geometry(), context.scene());
            terrain.publishMaterialLookup(request.lookup);
            frameSelection = MinecraftFrameSelector.install(context.scene(), programs.waterVolume(),
                    request.published.gpu().fallbackBindingData(),
                    request.published.gpu().fallbackInstanceData());
        } catch (RuntimeException | Error failure) {
            if (frameSelection != null) frameSelection.close();
            terrain.stop();
            if (sky != null) sky.close();
            throw failure;
        }

        ArrayList<RetiredPrograms> delayed = new ArrayList<>();
        if (displaced != null) {
            delayed.addAll(displaced.delayed);
            delayed.add(new RetiredPrograms(displaced.sky, displaced.registration));
        }
        Active replacement = new Active(request.generation, registration, terrain,
                frameSelection, sky, delayed);
        request.registration = null;
        request.published = null;
        pending = null;
        active = replacement;
        if (sky == null) replacement.releaseDisplacedPrograms();
    }

    private PassRegistration createSky(MinecraftPrograms programs, long generation) {
        if (!context.dimension().id().equals(OVERWORLD)) return null;
        return context.renderSession().passes().addWorldResourcePass(setup ->
                new FirstRecordPass(new SkyLutPass(setup.gpu(), MinecraftProgramSession::options,
                        programs.environment(), context.environment(), generation),
                        () -> skySelected(generation)));
    }

    private synchronized void skySelected(long generation) {
        if (active != null && active.generation == generation) active.releaseDisplacedPrograms();
    }

    private static Roots roots(MinecraftProgramResources.Epoch epoch) {
        return new Roots(epoch.implementationData(), epoch.fallbackBindingData(),
                epoch.fallbackInstanceData(), epoch.retirement());
    }

    static ProgramRegistration<MinecraftPrograms> registerPrograms(ProgramChannel channel, Roots roots) {
        return channel.register(builder -> {
            var coverage = shader("caustica_minecraft_coverage", "MinecraftCoverage");
            var material = new SurfaceDefinition<>(shader("caustica_minecraft_surface", "MinecraftSurface"),
                    coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                    MinecraftProgramTypes.INSTANCE_DATA, roots.retirement());
            return new MinecraftPrograms(builder.surface(material),
                    builder.surface(SurfaceDefinition.of(shader("caustica_water_surface", "WaterSurface"),
                            coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.surface(SurfaceDefinition.of(shader("caustica_portal_surface", "PortalSurface"),
                            coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.volume(VolumeDefinition.of(shader("caustica_water_surface", "WaterVolume"),
                            roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.environment(new EnvironmentDefinition<>(
                            shader("caustica_minecraft_overworld_sky", "MinecraftOverworldSky"),
                            MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA)));
        });
    }

    private static OptionValues options() {
        return CausticaSettings.getInstance().lookup().snapshot().options(MinecraftProvidersExtension.ID);
    }

    private static MinecraftLightProvider.CelestialSettings celestialSettings() {
        OptionValues values = options();
        return new MinecraftLightProvider.CelestialSettings(
                values.get(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES),
                values.get(SkyLutPass.SUN_ANGULAR_RADIUS_DEGREES),
                values.get(SkyLutPass.MOON_ANGULAR_RADIUS_DEGREES));
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        overlayRegistration.close();
        lightRegistration.close();
        if (pending != null && pending.upload != null) pending.upload.close();
        if (active != null && active.sky != null) active.sky.close();
        if (active != null) active.stopSceneProducers();
        lights.close();
        if (pending != null) pending.close();
        pending = null;
        if (active != null) active.closePrograms();
        active = null;
    }

    @Override public void close() { resources.close(); }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(SHADERS, module, type);
    }

    record Roots(ShaderData<MinecraftProgramTypes.ImplementationData> implementation,
                 ShaderData<MinecraftProgramTypes.PrimitiveData> fallbackBinding,
                 ShaderData<MinecraftProgramTypes.InstanceData> fallbackInstance, Runnable retirement) {
        Roots {
            java.util.Objects.requireNonNull(implementation, "implementation");
            java.util.Objects.requireNonNull(fallbackBinding, "fallbackBinding");
            java.util.Objects.requireNonNull(fallbackInstance, "fallbackInstance");
            java.util.Objects.requireNonNull(retirement, "retirement");
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
        MinecraftProgramResources.PublishedEpoch published;
        Pending(long generation, MinecraftMaterialLookup lookup) {
            this.generation = generation;
            this.lookup = lookup;
        }
        void close() {
            if (upload != null) upload.close();
            if (registration != null) registration.close();
            upload = null;
            registration = null;
            published = null;
        }
    }

    private final class Active {
        final long generation;
        final ProgramRegistration<MinecraftPrograms> registration;
        final MinecraftTerrainSession terrain;
        final MinecraftFrameSelector.Lease frameSelection;
        final PassRegistration sky;
        final ArrayList<RetiredPrograms> delayed;
        boolean producersStopped;
        Active(long generation, ProgramRegistration<MinecraftPrograms> registration,
               MinecraftTerrainSession terrain, MinecraftFrameSelector.Lease frameSelection,
               PassRegistration sky, ArrayList<RetiredPrograms> delayed) {
            this.generation = generation;
            this.registration = registration;
            this.terrain = terrain;
            this.frameSelection = frameSelection;
            this.sky = sky;
            this.delayed = delayed;
        }
        void stopSceneProducers() {
            if (producersStopped) return;
            producersStopped = true;
            frameSelection.close();
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
        }
    }

    private record RetiredPrograms(PassRegistration sky, ProgramRegistration<MinecraftPrograms> registration) {
        void close() {
            if (sky != null) sky.close();
            registration.close();
        }
    }

    private static final class LightUpdatePass implements Pass<PassFrame> {
        private final MinecraftLightProvider lights;
        LightUpdatePass(MinecraftLightProvider lights) { this.lights = lights; }
        @Override public void record(PassFrame frame) { lights.update(); }
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
