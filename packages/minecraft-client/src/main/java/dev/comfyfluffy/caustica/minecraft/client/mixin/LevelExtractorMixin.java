package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry.Callback;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards block edits to RT and suspends vanilla extraction while RT owns the world.
 * Block-change entry points let RtTerrain include neighboring section boundaries. Lighting-only
 * section invalidations do not change ray-traced geometry and therefore need no extraction.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Unique private boolean caustica$rebuildingVanillaTerrain;

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void caustica$resetTerrainSuspension(ClientLevel level, CallbackInfo ci) {
        CausticaClientComposition.current().renderController().resetVanillaTerrain();
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void caustica$resumeVanillaTerrain(DeltaTracker deltaTracker, Camera camera,
                                              float deltaPartialTick, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("world.resumeGate")) {
            CausticaClientComposition.current().renderController().resumeVanillaTerrain(() -> {
                try (var ignored = CausticaClientComposition.current().runtime().profileStage("host.worldMaintenance")) {
                    caustica$rebuildingVanillaTerrain = true;
                    try { ((LevelExtractor) (Object) this).allChanged(); }
                    finally { caustica$rebuildingVanillaTerrain = false; }
                }
            });
        }
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void caustica$invalidateRenderState(CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("terrain.invalidate")) {
            if (caustica$rebuildingVanillaTerrain) return;
            try (var ignored = CausticaClientComposition.current().runtime().profileStage("terrain.markDirty")) {
                CausticaClientComposition.current().renderController().resetFailureLatch();
                CausticaClientComposition.current().terrain().requestFullClear();
            }
        }
    }

    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void caustica$rtBlockChanged(BlockPos pos, int updateFlags, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("terrain.blockChanged")) {
            try (var ignored = CausticaClientComposition.current().runtime().profileStage("terrain.markDirty")) {
                if (CausticaClientComposition.current().runtime().hasSession()) {
                    CausticaClientComposition.current().terrain().markBlocksDirty(
                            pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void caustica$rtBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("terrain.blocksDirty");
             var ignored = CausticaClientComposition.current().runtime().profileStage("terrain.markDirty")) {
            if (CausticaClientComposition.current().runtime().hasSession()) {
                CausticaClientComposition.current().terrain().markBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
    }

    /** Suspended vanilla terrain rebuilds at the current camera when world replacement ends. */
    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/SectionUpdateTracker;repositionCamera(Lnet/minecraft/core/SectionPos;)V"))
    private void caustica$skipSuspendedTrackerRotation(SectionUpdateTracker tracker, SectionPos position,
                                                      Operation<Void> original) {
        try (var observation = MinecraftHostTelemetry.callback(Callback.TRACKER_ROTATION)) {
            if (CausticaClientComposition.current().renderController().vanillaTerrainSuspended()) return;
        }
        original.call(tracker, position);
    }

    @Inject(method = "applyFrustum", at = @At("HEAD"), cancellable = true)
    private void caustica$skipSuspendedFrustum(Frustum frustum, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("world.frustumGate")) {
            if (CausticaClientComposition.current().renderController().vanillaTerrainSuspended()) ci.cancel();
        }
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;expectedChunks()Lit/unimi/dsi/fastutil/longs/LongCollection;"))
    private LongCollection caustica$skipSuspendedExpectedChunks(LevelRenderer renderer,
                                                               Operation<LongCollection> original) {
        try (var observation = MinecraftHostTelemetry.callback(Callback.EXPECTED_CHUNKS)) {
            if (CausticaClientComposition.current().renderController().vanillaTerrainSuspended()) return LongSets.EMPTY_SET;
        }
        return original.call(renderer);
    }

    /** No vanilla terrain extraction is submitted while RT owns rendering. */
    @WrapOperation(method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/SectionUpdateTracker;getDirtyState(J)"
                            + "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;"))
    private SectionUpdateTracker.SectionDirtyState caustica$hideDirtySectionsFromVanilla(
            SectionUpdateTracker tracker, long sectionNode, Operation<SectionUpdateTracker.SectionDirtyState> original) {
        try (var observation = MinecraftHostTelemetry.callback(Callback.DIRTY_SECTION)) {
            if (CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) return null;
        }
        return original.call(tracker, sectionNode);
    }

    /**
     * Skip vanilla's entity render-state build while RT owns the world. {@code RtEntities} runs its own
     * pass over {@code level.entitiesForRendering()} straight into {@code EntityRenderDispatcher}, and the
     * states built here are only ever consumed by {@code LevelRenderer.submitFeatures}, which
     * {@link LevelRendererMixin} cancels. Leaves {@code lastEntityRenderStateCount} — F3's {@code E:}
     * counter — frozen at its last vanilla-rendered value.
     */
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"), cancellable = true)
    private void caustica$skipVanillaEntityExtraction(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
            LevelRenderState output, CallbackInfo ci) {
        try (var hostWork = MinecraftHostTelemetry.work("world.entityGate")) {
            if (CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) {
                ci.cancel();
            }
        }
    }

    /**
     * Skip vanilla's particle render-state build while RT owns the world. {@code RtEntities} captures the
     * live {@code ParticleGroup} objects instead (see {@link ParticleEngineAccessor}), because the packed
     * state built here carries no per-particle identity for motion vectors.
     *
     * <p>Safe to drop mid-session: each {@code ParticleGroup} appends into one long-lived render state that
     * is cleared only by {@code ParticlesRenderState.reset()} over the groups added the previous frame.
     * {@code extract} runs that reset before reaching this call, so skipping the fill leaves those states
     * empty rather than stale, and vanilla resumes on clean buffers when RT stops.</p>
     */
    @WrapOperation(method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;extract("
                            + "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;"
                            + "Lnet/minecraft/client/renderer/culling/Frustum;"
                            + "Lnet/minecraft/client/Camera;F)V"))
    private void caustica$skipVanillaParticleExtraction(ParticleEngine engine, ParticlesRenderState particles,
            Frustum frustum, Camera camera, float partialTick, Operation<Void> original) {
        try (var observation = MinecraftHostTelemetry.callback(Callback.PARTICLES)) {
            if (CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) return;
        }
        original.call(engine, particles, frustum, camera, partialTick);
    }

    /**
     * Skip vanilla's weather column build while RT owns the world. It walks a
     * {@code (2 * weatherRadius + 1)^2} column grid with a heightmap, biome and light lookup each, every
     * frame it is raining or snowing, and RT does not render weather — so none of it is ever drawn.
     * {@code WeatherRenderState.reset()} zeroes the column lists and the intensity at the top of
     * {@code extract}, so skipping the fill leaves the state empty rather than stale.
     */
    @WrapOperation(method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;extractRenderState("
                            + "Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/world/phys/Vec3;"
                            + "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V"))
    private void caustica$skipVanillaWeatherExtraction(WeatherEffectRenderer renderer, ClientLevel level,
            float partialTicks, Vec3 cameraPos, WeatherRenderState state, Operation<Void> original) {
        try (var observation = MinecraftHostTelemetry.callback(Callback.WEATHER)) {
            if (CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) return;
        }
        original.call(renderer, level, partialTicks, cameraPos, state);
    }

}
