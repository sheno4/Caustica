package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;

/** Original density generation and independent cloud reconstruction, composed after world reconstruction. */
public final class CloudlyOriginalPass implements Pass<PostEffectFrame> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudlyOriginalPass.class);
    private final GpuDevice gpu;
    private final GpuComputeQueue compute;
    private final ResourceFactory resources;
    private final Supplier<OptionValues> options;
    private final Supplier<MinecraftSkyFrame> frames;
    private final CloudlySkyPreset sky;
    private final List<GpuComputeJob> jobs = new ArrayList<>();
    private final List<AutoCloseable> staged = new ArrayList<>();
    private final Thread worker;
    private final ResourceOwner preparationLifetime;
    private volatile Assets assets;
    private volatile boolean closed;
    private volatile Throwable preparationFailure;
    private boolean preparationFinished;
    private Epoch epoch;

    public static Optional<Path> projectManifest(CloudlySourcePack source) {
        Object value = source.source().get("nativePipelineManifest");
        return value instanceof String path && !path.isBlank()
                ? Optional.of(source.manifest().getParent().resolve(path)) : Optional.empty();
    }

    public CloudlyOriginalPass(PostEffectSetup setup, GpuComputeQueue compute, ResourceFactory resources,
                              Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames,
                              CloudlySourcePack source) {
        this(setup, compute, resources, options, frames, source, projectManifest(source).orElseThrow());
    }

    public CloudlyOriginalPass(PostEffectSetup setup, GpuComputeQueue compute, ResourceFactory resources,
                              Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames,
                              CloudlySourcePack source, Path manifest) {
        gpu = setup.gpu(); this.compute = compute; this.resources = resources;
        this.options = options; this.frames = frames; sky = CloudlySkyPreset.from(source);
        worker = Thread.ofVirtual().name("Caustica original Cloudly GPU preparation").unstarted(() -> prepare(source, manifest));
        synchronized (this) {
            preparationLifetime = resources.create(this::retirePreparation);
            try { worker.start(); }
            catch (RuntimeException | Error failure) {
                ResourceLifetime.closeAfterFailure(failure, preparationLifetime::close);
                throw failure;
            }
        }
    }

    public boolean resourcesReady() { return assets != null; }
    public Optional<Throwable> preparationFailure() { return Optional.ofNullable(preparationFailure); }

    private synchronized <T extends AutoCloseable> void stage(CompletableFuture<T> future, T value) {
        if (closed || preparationFinished) { closeAll(List.of(value)); future.cancel(false); }
        else { staged.add(value); future.complete(value); }
    }

    private synchronized void track(GpuComputeJob job) {
        if (closed || preparationFinished) job.close(); else jobs.add(job);
    }

    /** A completion callback owns its output until the preparation worker takes that exact claim. */
    private <T extends AutoCloseable> T take(CompletableFuture<T> future) throws Exception {
        T value = future.get();
        synchronized (this) {
            requirePreparing();
            if (!staged.remove(value)) throw new IllegalStateException("Original Cloudly preparation output has no staged owner");
            return value;
        }
    }

    private synchronized void requirePreparing() throws InterruptedException {
        if (closed || preparationFinished || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private void prepare(CloudlySourcePack source, Path manifest) {
        List<AutoCloseable> acquired = new ArrayList<>();
        try {
            var project = CloudlyNativeProject.load(manifest);
            requirePreparing();
            LOGGER.info("Original Cloudly: preparing source volume upload, density bake, atmosphere and native textures");
            var volumeFuture = new CompletableFuture<CloudlyCloudResources>();
            track(CloudlyVolumeUpload.prepare(gpu, compute, resources, source, result -> {
                if (result instanceof CloudlyVolumeUpload.Ready ready) stage(volumeFuture, ready.resources());
                else if (result instanceof CloudlyVolumeUpload.Failed failed) volumeFuture.completeExceptionally(failed.failure());
                else volumeFuture.cancel(false);
            }));
            var textureFuture = new CompletableFuture<CloudlyFrameTextures.Ready>();
            Map<String, CloudlyVolumeBake.SamplerSpec> samplers = new LinkedHashMap<>();
            for (var texture : project.textures().textures()) {
                boolean blue = texture.name().equals("BlueNoise");
                samplers.put(texture.name(), new CloudlyVolumeBake.SamplerSpec(texture.name(), blue ? VK_FILTER_NEAREST : VK_FILTER_LINEAR,
                        blue ? VK_FILTER_NEAREST : VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                        blue ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                        blue ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                        blue ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 0, Float.MAX_VALUE));
            }
            track(CloudlyFrameTextures.prepare(gpu, compute, resources, project.textures(), samplers, true, result -> {
                if (result instanceof CloudlyFrameTextures.Ready ready) stage(textureFuture, ready);
                else if (result instanceof CloudlyFrameTextures.Failed failed) textureFuture.completeExceptionally(failed.failure());
                else textureFuture.cancel(false);
            }));
            var atmoFuture = new CompletableFuture<CloudlyOriginalBakeResources.Ready>();
            track(project.atmosphere().prepare(gpu, compute, resources, result -> finishBake(atmoFuture, result)));
            var uploaded = take(volumeFuture); acquired.add(uploaded);
            var densityFuture = new CompletableFuture<CloudlyOriginalBakeResources.Ready>();
            track(project.density().prepare(gpu, compute, resources, uploaded, result -> finishBake(densityFuture, result)));
            var density = take(densityFuture); acquired.add(density);
            var atmosphere = take(atmoFuture); acquired.add(atmosphere);
            var textures = take(textureFuture); acquired.add(textures);
            uploaded.close(); acquired.remove(uploaded);
            requirePreparing();
            var sphere = CloudlySourceBuffer.uploadFloat4Texels(gpu, resources, ByteBuffer.wrap(Files.readAllBytes(project.sphereSamples())), List.of());
            acquired.add(sphere);
            Set<String> programIds = new LinkedHashSet<>();
            project.schedule().stages().stream().filter(stage -> stage.program() != null).forEach(stage -> programIds.add(stage.program().id()));
            requirePreparing();
            var programs = new CloudlyFramePrograms(gpu, resources, project.library(), programIds); acquired.add(programs);
            requirePreparing();
            var color = new CloudlyColorBridge(gpu, resources); acquired.add(color);
            requirePreparing();
            var input = ShaderObjectCompute.load(gpu, CloudlyOriginalPass.class, "/caustica/shaders/pipelines/cloudly/native_inputs.comp.spv"); acquired.add(input);
            requirePreparing();
            var timing = new CloudlyGpuTiming(gpu, resources); acquired.add(timing);
            List<AutoCloseable> owned = List.copyOf(acquired);
            ResourceOwner owner = resources.create(() -> closeAll(owned));
            acquired.clear();
            var completed = new Assets(project, density, atmosphere, textures, sphere, programs, color, input, timing, owner);
            synchronized (this) {
                preparationFinished = true;
                jobs.clear();
                if (closed) owner.close();
                else { assets = completed; LOGGER.info("Original Cloudly GPU resources ready: {} density outputs, {} native programs", density.outputBindings().size(), programIds.size()); }
            }
        } catch (Throwable failure) {
            List<AutoCloseable> pending;
            List<GpuComputeJob> pendingJobs;
            synchronized (this) {
                preparationFinished = true;
                pending = List.copyOf(staged); staged.clear();
                pendingJobs = List.copyOf(jobs); jobs.clear();
            }
            ResourceLifetime.closeAfterFailure(failure, () -> closeAll(pendingJobs),
                    () -> closeAll(acquired), () -> closeAll(pending));
            preparationFailure = failure;
            if (!closed && !(failure instanceof InterruptedException)) LOGGER.error("Original Cloudly GPU preparation failed; original rendering is unavailable", failure);
        } finally {
            preparationLifetime.close();
        }
    }

    /** The resource retirement worker drains CPU allocations before device teardown can complete. */
    private void retirePreparation() {
        List<GpuComputeJob> cancel;
        synchronized (this) {
            if (!preparationFinished) {
                preparationFinished = true;
                worker.interrupt();
            }
            cancel = List.copyOf(jobs); jobs.clear();
        }
        new ResourceLifetime(() -> closeAll(cancel), this::awaitPreparation).close();
    }

    private void awaitPreparation() {
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); }
            catch (InterruptedException cancellation) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void finishBake(CompletableFuture<CloudlyOriginalBakeResources.Ready> future, CloudlyOriginalBakeResources.Completion result) {
        if (result instanceof CloudlyOriginalBakeResources.Ready ready) stage(future, ready);
        else if (result instanceof CloudlyOriginalBakeResources.Failed failed) future.completeExceptionally(failed.failure());
        else future.cancel(false);
    }

    @Override public synchronized void record(PostEffectFrame frame) {
        Assets ready = assets;
        MinecraftSkyFrame captured = frames.get();
        OptionValues values = options.get();
        if (closed || ready == null || captured == null || !values.get(CloudlyCloudPass.ENABLED)) return;
        frame.retain(ready.owner);
        var view = frame.view().camera();
        var current = new CloudlyFrameCamera(frame.cameraRelativeFromClip(), view.x(), view.y(), view.z(),
                captured.celestial().seaLevel(), frame.metersPerSceneUnit());
        Map<String, double[]> inputs = frameInputs(frame, captured, values, ready, current);
        var source = shadowSettings(ready.project);
        double[] sun = inputs.get("frame.sunDirectionSource"), light = inputs.get("frame.lightDirectionSource");
        float sunElevation = (float)Math.toDegrees(Math.asin(sun[2]));
        var angles = new CloudlyShadowState.LightAngles(sunElevation, (float)Math.toDegrees(Math.asin(light[2])),
                (float)(Math.toDegrees(Math.atan2(light[1], light[0])) + 90));
        var choices = new CloudlyShadowState.HostChoices(true, false, true, true, true, false, true, true);
        var geometry = CloudlyShadowState.geometry(source, angles, choices);
        put(inputs, "frame.shadowNominalSize", geometry.extent().values());
        put(inputs, "frame.shadowHalfSize", geometry.extent().width()/2, geometry.extent().height()/2, geometry.extent().depth());
        put(inputs, "frame.shadowSamplingSize", geometry.extent().width()/4, geometry.extent().height()/4, geometry.extent().depth());
        put(inputs, "frame.shadowBlurRadius", 1 + ((90f-sunElevation)*0.3333333432674408f > 2 ? 1 : 0));
        put(inputs, "nativeFrame.shadowSplitGrid", source.splitX(), source.splitY());
        var resolved = ready.project.schedule().resolve(inputs);
        if (epoch == null || epoch.initializationRecorded && !epoch.initialized
                || !epoch.extents.images().equals(resolved.images()) || !epoch.extents.buffers().equals(resolved.buffers())) {
            Epoch previous = epoch;
            epoch = allocateEpoch(frame, ready.project.schedule(), resolved);
            if (previous != null) previous.close();
        }
        Epoch target = epoch;
        frame.retain(target.owner);
        CloudlyFrameCamera previousCamera = target.previous == null ? current : target.previous;
        put(inputs, "frame.previousPreExposure", target.previous == null ? frame.preExposure() : target.previousExposure);
        put(inputs, "frame.historyAvailable", target.previous == null ? 0 : 1);
        put(inputs, "frame.cameraReset", target.previous == null ? 1 : 0);
        double speed = 0;
        if (target.previous != null) {
            float[] a = current.sourceOriginMeters(), b = target.previous.sourceOriginMeters();
            speed = Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2));
        }
        put(inputs, "frame.cameraSpeedMeters", speed);
        float[] origin = current.sourceOriginMeters();
        var cameraCm = new CloudlyShadowState.CameraCentimeters(origin[0]*100., origin[1]*100., origin[2]*100.);
        int number = (int)frame.frameIndex();
        var shadow0 = CloudlyShadowState.plan(target.shadow0, source, choices, angles, geometry, 0, number, cameraCm, target.previous == null, target.previous == null);
        var shadow1 = CloudlyShadowState.plan(target.shadow1, source, choices, angles, geometry, 1, number, cameraCm, target.previous == null, target.previous == null);
        inputs.putAll(CloudlyShadowState.samplingInputs(geometry, shadow0.stateAfterGpuCompletion(), shadow1.stateAfterGpuCompletion(), number));
        Map<String, String> roles = roles(target, shadow0.stateAfterGpuCompletion(), shadow1.stateAfterGpuCompletion());
        CloudlyFrameRecipe.ViewWriter writer = (layout, bytes) -> CloudlyFrameUniforms.writeView(layout, bytes, current, previousCamera,
                frame.sceneColor().width(), frame.sceneColor().height(), 0,0,0,0, frame.preExposure(), (float)frame.timeSeconds(),
                target.previous == null ? (float)frame.timeSeconds() : target.previousTime, number);
        List<Draw> shadows = new ArrayList<>();
        prepareShadows(ready, target, shadow0, 0, inputs, writer, current, shadows);
        prepareShadows(ready, target, shadow1, 1, inputs, writer, current, shadows);
        Set<String> selected = new LinkedHashSet<>();
        Map<String, ResourceOwner> retainedDependencies = new LinkedHashMap<>();
        for (var stage : ready.project.schedule().stages()) {
            if (stage.name().startsWith("Shadow")) retainedDependencies.put(stage.name(), target.buffers.owner());
            else selected.add(stage.name());
        }
        GpuImage destination = frame.acquireSceneColorOutput();
        float exposure = frame.preExposure(), time = (float)frame.timeSeconds();
        Runnable advanceHistory = () -> {
            target.shadow0 = shadow0.stateAfterGpuCompletion(); target.shadow1 = shadow1.stateAfterGpuCompletion();
            target.flipped = !target.flipped; target.previous = current;
            target.previousExposure = exposure; target.previousTime = time;
        };
        CloudlyFrameExecutor.Plan plan = CloudlyFrameExecutor.preflight(ready.project.schedule(), resolved, selected, retainedDependencies,
                new CloudlyFrameExecutor.Inputs() {
                    @Override public CloudlyFrameExecutor.ComputeInput compute(CloudlyFrameSchedule.Stage stage) {
                        String id = stage.spec().getAsJsonObject("parameters").get("sourceProgramId").getAsString();
                        var constants = ready.project.recipes().get(id).encode(Set.of(id), inputs, writer).parameters(id);
                        CloudlyFrameExecutor.IndirectBuffer indirect = null;
                        if (resolved.dispatches().get(stage.name()) instanceof CloudlyFrameSchedule.IndirectDispatch dispatch) {
                            var buffer = target.buffers.indirectBuffer(dispatch.argumentBuffer(), dispatch.byteOffset());
                            indirect = new CloudlyFrameExecutor.IndirectBuffer(buffer.handle(), buffer.byteSize(), target.buffers.owner());
                        }
                        return new CloudlyFrameExecutor.ComputeInput(constants, (name, field) -> descriptor(ready, target, roles, name, field),
                                Map.of(), current, List.of(ready.owner, target.buffers.owner()), indirect);
                    }
                    @Override public CloudlyFrameExecutor.HostOperation host(CloudlyFrameSchedule.Stage stage) {
                        return switch (stage.operation()) {
                            case "CLEAR" -> new CloudlyFrameExecutor.HostOperation(f -> target.buffers.recordClear(f, stage.name()), List.of(target.buffers.owner()));
                            case "HOST_COMPOSITION_INPUTS" -> new CloudlyFrameExecutor.HostOperation(f -> importScene(frame, ready, target), List.of(target.buffers.owner()));
                            case "HOST_AFTER_RR_COMPOSITION" -> new CloudlyFrameExecutor.HostOperation(f -> ready.color.record(f, target.buffers.image("SceneColor").sampled(),
                                    destination.descriptor(GpuImageDescriptorKind.STORAGE).index(), destination.width(), destination.height(), true,
                                    List.of(ready.owner, target.buffers.owner())), List.of());
                            case "PING_PONG_SWAP" -> new CloudlyFrameExecutor.HostOperation(f -> f.whenSubmitted(advanceHistory), List.of());
                            default -> throw new IllegalArgumentException("Unimplemented original host operation " + stage.operation());
                        };
                    }
                });
        try (plan) {
            Set<String> requiredPrograms = new LinkedHashSet<>(plan.programIds());
            shadows.forEach(draw -> requiredPrograms.add(draw.program()));
            ready.programs.requirePrograms(requiredPrograms);
            if (!target.initialized) {
                target.initializationRecorded = true;
                target.buffers.recordInitialization(frame);
                ComputeSynchronization.initializeImages(frame.commandBuffer(), List.of(target.depth, target.white));
                clearWhite(frame, target.white);
                frame.whenSubmitted(() -> target.initialized = true);
            }
            int measurement = ready.timing.begin(frame, destination.width(), destination.height());
            for (Draw draw : shadows) draw.record(frame, ready.programs);
            plan.record(frame, ready.programs);
            ready.timing.end(frame, measurement);
            if (!target.reported) {
                frame.whenSubmitted(() -> {
                    target.reported = true;
                    LOGGER.info("Original Cloudly frame accepted: {} native stages, {} shadow dispatches, {}x{}; separate cloud history, world RR guides unchanged",
                            plan.steps().size(), shadows.size(), destination.width(), destination.height());
                });
            }
        }
    }

    private Epoch allocateEpoch(PostEffectFrame frame, CloudlyFrameSchedule schedule, CloudlyFrameSchedule.Resolved resolved) {
        List<AutoCloseable> acquired = new ArrayList<>();
        try {
            var buffers = CloudlyFrameResources.allocate(gpu, resources, schedule, resolved); acquired.add(buffers);
            var depth = VmaImage2D.create(gpu, frame.sceneColor().width(), frame.sceneColor().height(), VK_FORMAT_R32_SFLOAT,
                    "Original Cloudly host depth"); acquired.add(depth);
            var white = VmaImage2D.create(gpu, 1, 1, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    "Original Cloudly white occlusion"); acquired.add(white);
            return new Epoch(resources, buffers, resolved, depth, white);
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, () -> closeAll(acquired));
            throw failure;
        }
    }

    private void prepareShadows(Assets ready, Epoch target, CloudlyShadowState.Update<String> update, int cascade,
                                Map<String, double[]> inputs, CloudlyFrameRecipe.ViewWriter writer,
                                CloudlyFrameCamera camera, List<Draw> draws) {
        for (var work : update.stages()) {
            String name = switch (work.programId()) {
                case "69" -> "ShadowTrace"+cascade;
                case "115" -> "ShadowBlurX0";
                case "107" -> "ShadowBlurY0";
                case "114" -> "ShadowDownsample"+cascade;
                default -> throw new IllegalArgumentException("Unknown source shadow stage");
            };
            var stage = ready.project.schedule().stage(name);
            Map<String, double[]> patched = new LinkedHashMap<>(inputs); patched.putAll(work.inputs());
            var constants = ready.project.recipes().get(work.programId()).encode(Set.of(work.programId()), patched, writer).parameters(work.programId());
            if (!work.programId().equals("69")) for (var override : work.inputs().entrySet()) {
                var field = stage.program().parametersLayout().field(override.getKey());
                ByteBuffer mutable = ByteBuffer.allocate(constants.remaining()).order(ByteOrder.LITTLE_ENDIAN).put(constants.duplicate()).flip();
                if (field.type().startsWith("float")) {
                    float[] values = new float[override.getValue().length];
                    for (int i=0;i<values.length;i++) values[i]=(float)override.getValue()[i];
                    stage.program().parametersLayout().putFloats(mutable, override.getKey(), values);
                } else {
                    int[] values = Arrays.stream(override.getValue()).mapToInt(value -> (int)value).toArray();
                    stage.program().parametersLayout().putInts(mutable, override.getKey(), values);
                }
                constants = mutable.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            }
            Map<String, String> roles = new LinkedHashMap<>();
            for (String suffix : List.of("Low", "Blur", "Sampling")) roles.put("Shadow"+cascade+"Work"+suffix, work.workResources()+suffix);
            var table = ready.project.schedule().bindings(name, (resource, field) -> descriptor(ready, target, roles, resource, field));
            draws.add(new Draw(work.programId(), constants, table.descriptors(), camera, target.buffers.owner(), work.groupsX(), work.groupsY(), work.groupsZ()));
        }
    }

    private GpuDescriptorIndex descriptor(Assets ready, Epoch target, Map<String,String> roles, String name, CloudlyShaderLibrary.BindingField field) {
        String actual = roles.getOrDefault(name, name);
        if (target.buffers.images().containsKey(actual) || target.buffers.buffers().containsKey(actual)
                || ready.project.schedule().resources().stream().anyMatch(value -> value.name().equals(actual) && value.kind() == CloudlyFrameSchedule.Kind.SAMPLER)) {
            return target.buffers.descriptor(actual, field);
        }
        return switch (actual) {
            case "SceneDepth" -> target.depth.sampledIndex();
            case "OriginalBlueNoise", "InactiveStars", "InactiveGround2D", "InactiveGBuffer", "InactiveHeterogeneous", "InactiveSkyGodrayHistory" -> ready.textures.descriptors().get("BlueNoise");
            case "OriginalMoonTexture", "InactiveStarMoonCube", "InactiveFogCube" -> ready.textures.descriptors().get("MoonTexture");
            case "OriginalSphereSamples" -> ready.sphere.descriptor();
            case "OpticalDepth" -> ready.atmosphere.texture("OpticalDepthTex").sampledIndex();
            case "MultiScattering" -> ready.atmosphere.texture("MultiScatteringLut").sampledIndex();
            case "BASE_SDF", "InactiveAO", "InactiveRain", "InactiveFogVolume" -> ready.density.texture("CloudTex_BASE_SDF_Tex").sampledIndex();
            case "CIRRUS_SDF" -> ready.density.texture("CloudTex_CIRRUS_SDF_Tex").sampledIndex();
            case "DISP1", "InactiveAlto" -> ready.density.texture("CloudTex_DISP1_DensityTex").sampledIndex();
            case "DISP2" -> ready.density.texture("CloudTex_DISP2_DensityTex").sampledIndex();
            case "DISP_CIRRUS" -> ready.density.texture("CloudTex_DISP_CIRRUS_DensityTex").sampledIndex();
            case "InactiveAPHistory" -> target.buffers.image("AerialCurrent").sampled();
            case "WhiteOcclusion" -> target.white.sampledIndex();
            case "CloudSampler" -> ready.density.outputs().sampler("source-bilinear-wrap");
            case "BaseSampler", "CirrusSampler" -> ready.density.outputs().sampler("source-bilinear-wrapxy-clampz");
            default -> throw new IllegalArgumentException("Original frame has no actual producer for " + actual);
        };
    }

    private void importScene(PostEffectFrame frame, Assets ready, Epoch target) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var push = stack.malloc(32).order(ByteOrder.LITTLE_ENDIAN);
            push.putInt(frame.sceneColor().descriptor(GpuImageDescriptorKind.SAMPLED).index().value())
                    .putInt(frame.primaryDepth().descriptor(GpuImageDescriptorKind.SAMPLED).index().value())
                    .putInt(target.buffers.image("SceneColor").storage(0).value()).putInt(target.depth.storageIndex().value())
                    .putInt(target.buffers.image("Velocity").storage(0).value()).putInt(0)
                    .putFloat(frame.traceJitter()[0]).putFloat(frame.traceJitter()[1]).flip();
            ready.input.dispatch(frame.commandBuffer(), push, Math.ceilDiv(frame.sceneColor().width(),8),Math.ceilDiv(frame.sceneColor().height(),8),1);
            memoryBarrier(frame, stack);
        }
    }

    private static void clearWhite(PostEffectFrame frame, VmaImage2D image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue value = VkClearColorValue.calloc(stack).float32(0,1).float32(1,1).float32(2,1).float32(3,1);
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(frame.commandBuffer(), image.image(), VK_IMAGE_LAYOUT_GENERAL, value, range);
            var barrier = VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT);
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(),VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }
    private static void memoryBarrier(PostEffectFrame frame, MemoryStack stack) {
        var barrier = VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT|VK13.VK_ACCESS_2_SHADER_WRITE_BIT).dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT|VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
        VK14.vkCmdPipelineBarrier2(frame.commandBuffer(),VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private Map<String, double[]> frameInputs(PostEffectFrame frame, MinecraftSkyFrame captured, OptionValues values, Assets ready, CloudlyFrameCamera camera) {
        var result = new LinkedHashMap<String,double[]>();
        int w=frame.sceneColor().width(),h=frame.sceneColor().height(),tw=w,th=h,hw=Math.ceilDiv(tw,2),hh=Math.ceilDiv(th,2),cw=Math.ceilDiv(tw,16),ch=Math.ceilDiv(th,16);
        put(result,"frame.outputSize",w,h); put(result,"frame.viewport",0,0,w,h); put(result,"frame.nativeSkyTraceSize",tw,th);
        put(result,"frame.adjustedTraceSize",hw,hh); put(result,"frame.checkerSize",Math.ceilDiv(hw,2),hh);
        put(result,"frame.nativeCheckerDispatchSize",hw/2,hh); put(result,"frame.distanceMaskSize",Math.ceilDiv(tw,8),Math.ceilDiv(th,8));
        put(result,"frame.coarseTraceSize",cw,ch); put(result,"frame.coarseTileCount",cw*ch); put(result,"frame.coarseDispatchSize",Math.ceilDiv(cw,8)*4,Math.ceilDiv(ch,8),1);
        var ap=CloudlyAtmosphereDimensions.aerialPerspective(w,h,(int)ready.project.scalar("aerialMaxLongEdge"),(int)ready.project.scalar("aerialDepth"));
        put(result,"frame.aerialVolumeSize",ap.values()); put(result,"frame.aerialVolumeDispatchSize",ap.width(),ap.height()); put(result,"frame.skyWithGodraysSize",ap.width(),ap.height());
        put(result,"frame.godrayFlagSize",64,64); put(result,"frame.number",frame.frameIndex()&0xffffffffL); put(result,"frame.temporalPhase",frame.frameIndex()%8);
        put(result,"frame.preExposure",frame.preExposure()); put(result,"frame.originalCloudAnimationTimeSeconds",frame.timeSeconds());
        put(result,"frame.originalRenderType",1); put(result,"frame.originalTileBasedHalfPixels",1); put(result,"frame.originalTraceSubpassIndex",1);
        put(result,"frame.allHighResTiles",1); put(result,"frame.betterEdgeClassification",1);
        put(result,"frame.maxDistanceMeters",ready.project.scalar("CloudMaxTraceDistanceInKm")*1000);
        put(result,"frame.sceneDepthTextureSize",w,h); put(result,"frame.sceneDepthViewportMax",w,h);
        put(result,"renderFovDegrees",Math.toDegrees(camera.verticalFieldOfView()));
        double tilt=Math.toRadians(values.get(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES)),a=captured.celestial().sunAngleRadians(),m=captured.celestial().moonAngleRadians();
        double[] sun={-Math.sin(a),-Math.sin(tilt)*Math.cos(a),Math.cos(tilt)*Math.cos(a)};
        double[] moon={-Math.sin(m),-Math.sin(tilt)*Math.cos(m),Math.cos(tilt)*Math.cos(m)};
        double elevation=Math.toDegrees(Math.asin(sun[2]));
        double fraction=captured.celestial().lighting().moonPhaseFixedFraction();
        double phaseScale=fraction+(1-fraction)*Math.abs(captured.celestial().moonPhaseIndex()-4.)/4.;
        double moonEnergy=sky.moonIlluminanceLux()*phaseScale;
        var color=sky.sunColorLinearBt709(); double[] solar={sky.sunIlluminanceLux()*color.red(),sky.sunIlluminanceLux()*color.green(),sky.sunIlluminanceLux()*color.blue()};
        boolean useSun=elevation>ready.project.scalar("SunMinElevationForLightSource");
        put(result,"frame.sunDirectionSource",sun); put(result,"frame.sunSourceRadiance",solar); put(result,"frame.sunElevationDegrees",elevation);
        put(result,"frame.lightDirectionSource",useSun?sun:moon); put(result,"frame.lightSourceRadiance",useSun?solar:new double[]{moonEnergy,moonEnergy,moonEnergy});
        put(result,"frame.lightElevationDegrees",Math.toDegrees(Math.asin((useSun?sun:moon)[2])));
        put(result,"frame.moonEnabled",ready.project.scalar("EnableMoon")); put(result,"frame.moonDirectionSource",moon);
        put(result,"frame.moonSourceRadiance",sky.moonIlluminanceLux()); put(result,"frame.moonPhase",captured.celestial().moonPhaseIndex()/8.);
        put(result,"frame.moonPhaseAngleRadians",ready.project.scalar("MoonPhaseAngle")); put(result,"frame.moonDiskBrightness",ready.project.scalar("MoonDiskBrightness"));
        put(result,"frame.moonDiscCosHalfAngle",Math.cos(Math.toRadians(ready.project.scalar("MoonDiskAngleInDegrees"))*0.5));
        for (var pair : Map.of("B","NoiseTexParamsB","C","NoiseTexParamsC","THINB_DISP","NoiseTexParams_THINB_DISP").entrySet()) {
            var stat=Objects.requireNonNull(ready.density.statistics().get(pair.getValue()),"Original density reduction has no "+pair.getValue());
            put(result,"noiseStatistics."+pair.getKey()+".min",stat.minimum().toDoubles()); put(result,"noiseStatistics."+pair.getKey()+".max",stat.maximum().toDoubles());
        }
        return result;
    }

    private static CloudlyShadowState.SourceSettings shadowSettings(CloudlyNativeProject p) {
        return new CloudlyShadowState.SourceSettings((float)p.scalar("EarthRadiusInKm"),(float)p.scalar("CloudStartHeightInKm"),(float)p.scalar("cloudMaxHeightMeters"),
                (float)p.scalar("CloudMaxTraceDistanceInKm"),p.scalar("FlatGameMap")!=0,p.scalar("EnableShadowParamsLowSunElevation")!=0,(float)p.scalar("ShadowParamsLowSunElevationSwitchAngleInDegrees"),
                new CloudlyShadowState.Extent((int)p.scalar("shadowWidth"),(int)p.scalar("shadowHeight"),(int)p.scalar("shadowDepth")),
                new CloudlyShadowState.Extent((int)p.scalar("lowShadowWidth"),(int)p.scalar("lowShadowHeight"),(int)p.scalar("lowShadowDepth")),
                (float)p.scalar("shadowTexelKm"),(float)p.scalar("lowShadowTexelKm"),(float)p.scalar("transitionX"),(float)p.scalar("transitionY"),
                p.scalar("centerCloudBandAtLowElevation")!=0,(float)p.scalar("centerCloudBandFactor"),(int)p.scalar("shadowSplitFrames"),(int)p.scalar("shadowSplitX"),(int)p.scalar("shadowSplitY"));
    }
    private static void put(Map<String,double[]> map,String key,double... value) { map.put(key,value.clone()); }

    private static Map<String,String> roles(Epoch target,CloudlyShadowState.Cache<String> a,CloudlyShadowState.Cache<String> b) {
        Map<String,String> result=new LinkedHashMap<>();
        for(int level=0;level<2;level++) {
            var cache=level==0?a:b;
            for(String suffix:List.of("Low","Blur","Sampling")) {
                result.put("Shadow"+level+"Current"+suffix,cache.current().resources()+suffix);
                result.put("Shadow"+level+"Next"+suffix,cache.next().resources()+suffix);
                result.put("Shadow"+level+"Work"+suffix,cache.work().resources()+suffix);
            }
        }
        if(target.flipped) for(String suffix:List.of("Color","Distance","Visibility","Aux","Info")) {
            result.put("ResolvedCurrent"+suffix,"ResolvedPrevious"+suffix); result.put("ResolvedPrevious"+suffix,"ResolvedCurrent"+suffix);
        }
        return result;
    }

    private static CloudlyShadowState.Cache<String> emptyCache(int level) {
        var center=new CloudlyShadowState.Vector(0,0,0);
        return new CloudlyShadowState.Cache<>(new CloudlyShadowState.ResourceRecord<>(center,"Shadow"+level+"Current",false),
                new CloudlyShadowState.ResourceRecord<>(center,"Shadow"+level+"Next",false),new CloudlyShadowState.ResourceRecord<>(center,"Shadow"+level+"Work",false),512,2,0,0,0);
    }

    private record Draw(String program,ByteBuffer constants,Map<String,GpuDescriptorIndex> descriptors,CloudlyFrameCamera camera,ResourceOwner owner,int x,int y,int z) {
        void record(PostEffectFrame frame,CloudlyFramePrograms programs) { programs.record(frame,program,constants,descriptors,Map.of(),camera,List.of(owner),x,y,z); }
    }
    private record Assets(CloudlyNativeProject project,CloudlyOriginalBakeResources.Ready density,CloudlyOriginalBakeResources.Ready atmosphere,
                          CloudlyFrameTextures.Ready textures,CloudlySourceBuffer sphere,CloudlyFramePrograms programs,CloudlyColorBridge color,
                          ShaderObjectCompute input,CloudlyGpuTiming timing,ResourceOwner owner) { }
    private static final class Epoch implements AutoCloseable {
        final CloudlyFrameResources buffers; final CloudlyFrameSchedule.Resolved extents; final VmaImage2D depth,white;
        final ResourceOwner owner;
        CloudlyShadowState.Cache<String> shadow0=emptyCache(0),shadow1=emptyCache(1);
        CloudlyFrameCamera previous; float previousExposure,previousTime; boolean initializationRecorded,initialized,flipped,reported;
        Epoch(ResourceFactory resources,CloudlyFrameResources buffers,CloudlyFrameSchedule.Resolved extents,VmaImage2D depth,VmaImage2D white) {
            this.buffers=buffers;this.extents=extents;this.depth=depth;this.white=white;
            this.owner=resources.create(() -> new ResourceLifetime(buffers::close,depth::close,white::close).close());
        }
        @Override public void close() { owner.close(); }
    }
    private static void closeAll(List<? extends AutoCloseable> objects) {
        new ResourceLifetime(objects.stream().<Runnable>map(object -> () -> {
            try { object.close(); } catch(RuntimeException failure) { throw failure; } catch(Exception failure) { throw new IllegalStateException(failure); }
        }).toArray(Runnable[]::new)).close();
    }
    @Override public void close() {
        List<AutoCloseable> release = new ArrayList<>();
        synchronized (this) {
            if(closed)return;
            closed=true;
            worker.interrupt();
            release.addAll(jobs); jobs.clear();
            release.addAll(staged); staged.clear();
            if(epoch!=null) { release.add(epoch); epoch=null; }
            if(assets!=null) { release.add(assets.owner); assets=null; }
            release.add(preparationLifetime);
        }
        try { closeAll(release); }
        catch (RuntimeException | Error failure) { LOGGER.error("Original Cloudly resource release failed", failure); }
    }
}
