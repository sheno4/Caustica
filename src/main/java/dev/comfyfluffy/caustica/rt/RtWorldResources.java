package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.material.RtMaterialEpoch;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtShaderCode;
import dev.comfyfluffy.caustica.rt.pass.RenderPassManager;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import dev.comfyfluffy.caustica.rt.shader.WorldShaderCompiler;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;

import java.io.IOException;
import java.util.Map;

/**
 * Resource-pack/runtime-scoped GPU state shared by retained geometry and the active world program.
 *
 * <p>The pipeline and material epoch are one publication unit: descriptors must be detached before the
 * epoch is destroyed, and a newly published epoch requires a source-frame boundary before it may be traced.
 */
final class RtWorldResources {
    private final RtProgramManager programManager;
    private final ProviderManager providers;
    final RtMaterialEpoch materialEpoch;
    RtPipeline pipeline;
    int boundPassResourceGeneration = -1;
    boolean traceGate;

    RtWorldResources(RtProgramManager programManager, ProviderManager providers) {
        this.programManager = programManager;
        this.providers = providers;
        this.materialEpoch = new RtMaterialEpoch(providers);
    }

    void attachSceneGeometry(RtSceneGeometryManager geometry) {
        materialEpoch.attachSceneGeometry(geometry);
    }

    boolean requiresSourceFallback() {
        return pipeline == null
                || !materialEpoch.bindingsReady()
                || traceGate;
    }

    boolean completeStartupBoundary() {
        traceGate = false;
        return !requiresSourceFallback();
    }

    void invalidateMaterialBindings() {
        providers.onWorldChanged();
        traceGate = true;
    }

    RtPipeline ensureWorld(GpuContext context, RenderPassManager passManager, RtFrameResources frames)
            throws IOException {
        if (pipeline != null) {
            return pipeline;
        }
        RtProgramManager.Program program = programManager.candidate();
        if (program == null) {
            Throwable failure = programManager.requestedFailure();
            if (failure != null) {
                throw new IllegalStateException("The initial world program failed to compile", failure);
            }
            return null;
        }

        int bindlessCapacity = RtMaterialEpoch.TEXTURE_CAPACITY;
        RtPipeline created = null;
        try {
            created = createPipeline(context, program, bindlessCapacity);
            if (frames.output != null) {
                created.setStorageImage(frames.output.view());
                frames.bindGuideImages(created);
            }
            materialEpoch.publish(context, created, bindlessCapacity, program.rejectedSurfaces());
            bindPassResources(created, program, passManager);
            invalidateMaterialBindings();
        } catch (Throwable failure) {
            try {
                if (created != null) {
                    created.destroy();
                }
            } catch (Throwable descriptorFailure) {
                failure.addSuppressed(descriptorFailure);
            } finally {
                try {
                    materialEpoch.destroyPublishedEpoch();
                } catch (Throwable epochFailure) {
                    failure.addSuppressed(epochFailure);
                }
            }
            throw failure;
        }
        pipeline = created;
        if (program != programManager.active()) {
            programManager.activate(program);
        }
        return pipeline;
    }

    void refreshPassResources(GpuContext context, RenderPassManager passManager) {
        if (pipeline == null || passManager.worldResourceGeneration() == boundPassResourceGeneration) {
            return;
        }
        context.waitIdle();
        bindPassResources(pipeline, programManager.active(), passManager);
    }

    void beginReload(GpuContext context, RenderPassManager passManager) {
        if (context == null && (pipeline != null || materialEpoch.hasPublishedResources())) {
            throw new IllegalStateException("Live RT material resources have no current GPU context");
        }
        materialEpoch.beginReload();
        if (context == null) {
            return;
        }
        context.gpuExecutor().drainAndWaitIdle();
        if (passManager != null) {
            passManager.onResourcePackClosing();
        }
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        materialEpoch.destroyPublishedEpoch();
    }

    void resourceReloadFailed() {
        materialEpoch.reloadFailed();
    }

    void resourcePackApplied(RenderPassManager passManager) {
        if (passManager != null) {
            passManager.onResourcePackApplied();
        }
        materialEpoch.resourcePackApplied();
    }

    private void bindPassResources(RtPipeline target, RtProgramManager.Program program,
            RenderPassManager passManager) {
        Map<String, WorldShaderCompiler.PassResourceBinding> bindings = program.passResourceBindings();
        for (var entry : passManager.worldResources().entrySet()) {
            WorldShaderCompiler.PassResourceBinding binding = bindings.get(entry.getKey());
            if (binding == null) {
                continue;
            }
            RenderPassManager.WorldResource resource = entry.getValue();
            if (resource.buffer() != null) {
                target.setPassResourceBuffer(binding.index(), resource.buffer().handle(), resource.buffer().size());
            } else {
                target.setPassResource(binding.index(), resource.view(), resource.sampler());
            }
        }
        boundPassResourceGeneration = passManager.worldResourceGeneration();
    }

    private static RtPipeline createPipeline(GpuContext context, RtProgramManager.Program program,
            int bindlessCapacity) {
        RtProgramManager.Shaders shaders = program.shaders();
        return RtPipeline.create(context, new RtShaderCode[]{shaders.primary(), shaders.indirect()},
                new RtShaderCode[]{shaders.skyMiss(), shaders.guideMiss()}, shaders.closestHit(),
                shaders.radianceAnyHit(), shaders.shadowAnyHit(), WorldPushConstantsData.BYTE_SIZE,
                bindlessCapacity, program.passResourceBindings());
    }

    void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        traceGate = false;
        boundPassResourceGeneration = -1;
        materialEpoch.destroy();
    }
}
