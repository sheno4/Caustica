package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.RtRuntime;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
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
    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void caustica$rtBlockChanged(BlockPos pos, int updateFlags, CallbackInfo ci) {
        if (RtRuntime.hasSession()) {
            RtTerrain.markBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void caustica$rtBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (RtRuntime.hasSession()) {
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
        return RtRuntime.active() ? null : original.call(tracker, sectionNode);
    }
}
