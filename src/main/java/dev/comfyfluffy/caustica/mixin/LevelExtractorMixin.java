package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.client.CausticaClientBootstrap;
import dev.comfyfluffy.caustica.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards vanilla's block-dirty signal to the RT renderer so edited sections (and their boundary
 * neighbours) re-extract. In 26.2 the dirty methods live on {@link LevelExtractor}. We hook the
 * <em>block-change</em> entry points and let {@link RtTerrain#markBlocksDirty} expand to sections:
 *
 * <ul>
 *   <li>{@code blockChanged(BlockPos, int)} — packet/prediction block changes.</li>
 *   <li>{@code setBlocksDirty(int×6)} — multi-block changes (explosions, etc.) and the
 *       {@code setBlockDirty(pos, old, new)} render-shape path.</li>
 * </ul>
 *
 * <p>We deliberately do <em>not</em> hook {@code setSectionDirty}: lighting-only invalidations
 * ({@code ClientChunkCache.onLightUpdate}) route straight through it, and we ray-trace lighting, so a
 * light change never alters our geometry. Hooking the block entry points keeps us off that churn.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void caustica$invalidateRenderState(CallbackInfo ci) {
        CausticaClientBootstrap.invalidateRenderState();
    }

    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void caustica$rtBlockChanged(BlockPos pos, int updateFlags, CallbackInfo ci) {
        if (CausticaClientComposition.current().runtime().hasSession()) {
            RtTerrain.markBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void caustica$rtBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (CausticaClientComposition.current().runtime().hasSession()) {
            RtTerrain.markBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    /**
     * Hide vanilla's dirty sections from {@code extract} while RT owns world rendering.
     *
     * <p>Reporting a section dirty here is destructive: {@code extract} immediately calls
     * {@code setNotDirty()} and hands the rebuild request to {@code LevelRenderer.compileSections}, which
     * {@link LevelRendererMixin} cancels. Left alone, that loses the dirty bit for every section touched
     * while RT is on, so those sections would still be showing pre-RT geometry when vanilla comes back.
     * Reporting nothing keeps {@link SectionUpdateTracker} accumulating dirtiness instead, so switching RT
     * off recompiles exactly the sections that changed — and vanilla does zero meshing work in the
     * meantime. The tracker's own camera repositioning still runs, and view-area rotation resets the
     * sections it recycles, so vanilla's terrain memory drains as the player moves.
     */
    @WrapOperation(method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/SectionUpdateTracker;getDirtyState(J)"
                            + "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;"))
    private SectionUpdateTracker.SectionDirtyState caustica$hideDirtySectionsFromVanilla(
            SectionUpdateTracker tracker, long sectionNode, Operation<SectionUpdateTracker.SectionDirtyState> original) {
        return CausticaClientComposition.current().runtime().active() ? null : original.call(tracker, sectionNode);
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
        if (CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) {
            ci.cancel();
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
        if (!CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) {
            original.call(engine, particles, frustum, camera, partialTick);
        }
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
        if (!CausticaClientComposition.current().renderController().rtOwnsWorldRendering()) {
            original.call(renderer, level, partialTicks, cameraPos, state);
        }
    }

}
