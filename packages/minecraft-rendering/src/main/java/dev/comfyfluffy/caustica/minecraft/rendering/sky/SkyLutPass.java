package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.CelestialAtlasImage;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.*;
import dev.comfyfluffy.caustica.settings.*;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;

/** Owns the Overworld atmosphere LUTs and publishes immutable environment binding revisions. */
public final class SkyLutPass implements Pass<PassFrame> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkyLutPass.class);
    public static final ResourceId ID = ResourceId.of("caustica", "sky_lut");
    private static final String SHADER_ROOT = "/caustica/shaders/pipelines/sky/";
    static final int TRANSMITTANCE_WIDTH = 256, TRANSMITTANCE_HEIGHT = 64;
    static final int MULTISCATTER_WIDTH = 32, MULTISCATTER_HEIGHT = 32;
    static final int SKY_VIEW_WIDTH = 192, SKY_VIEW_HEIGHT = 216;
    static final long PRIOR_SKY_READ_STAGE = KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
    static final long PRIOR_SKY_READ_ACCESS = VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
            | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
    static final long SKY_WRITE_STAGE = VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    static final long SKY_WRITE_ACCESS = VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;

    public static final String GROUP = "sky";
    public static final Option<Float> SUN_NOON_SOUTH_TILT_DEGREES = option("sun-noon-south-tilt-degrees", -89, 89, 30);
    public static final Option<Float> SUN_ANGULAR_RADIUS_DEGREES = option("sun-angular-radius-degrees", 0, 20, .6f);
    public static final Option<Float> MOON_ANGULAR_RADIUS_DEGREES = option("moon-angular-radius-degrees", 0, 20, 1.5f);
    public static final Option<Float> SUN_DISC_HALF_ANGLE_DEGREES = option("sun-disc-half-angle-degrees", 0, 45, 16.7f);
    public static final Option<Float> MOON_DISC_HALF_ANGLE_DEGREES = option("moon-disc-half-angle-degrees", 0, 45, 11.31f);
    public static final Option<Float> GROUND_ALBEDO = option("ground-albedo", 0, 1, .1f);
    public static final Option<Float> HORIZON_SOFTEN_DEGREES = option("horizon-soften-degrees", 0, 90, 15);
    public static final List<Option<?>> OPTIONS = List.of(SUN_NOON_SOUTH_TILT_DEGREES,
            SUN_ANGULAR_RADIUS_DEGREES, MOON_ANGULAR_RADIUS_DEGREES, SUN_DISC_HALF_ANGLE_DEGREES,
            MOON_DISC_HALF_ANGLE_DEGREES, GROUND_ALBEDO, HORIZON_SOFTEN_DEGREES);

    private final GpuDevice gpu;
    private final Supplier<OptionValues> options;
    private final Supplier<MinecraftSkyFrame> frames;
    private final EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment;
    private final MinecraftEnvironmentSelector selector;
    private final ResourceFactory resourceFactory;
    private final VmaImage2D transmittance, multiScatter, skyView;
    private final VulkanSampler lutSampler, celestialSampler;
    private final VmaMappedBuffer skyInputs;
    private final ShaderObjectCompute transmittanceShader, multiScatterShader, skyViewShader;
    private final SharedResource<AutoCloseable[]> resources;
    private final AtomicLong resourcePackEpoch;
    private ResourceOwner bindingOwner;
    private SharedResource<AtlasEntry> atlas;
    private boolean initialized, baked, closed;
    private float bakedGroundAlbedo;

    public SkyLutPass(GpuDevice gpu, Supplier<OptionValues> options,
                      Supplier<MinecraftSkyFrame> frames,
                      EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                      MinecraftEnvironmentSelector selector, ResourceFactory resourceFactory, long epoch) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.options = Objects.requireNonNull(options, "options");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.resourceFactory = Objects.requireNonNull(resourceFactory, "resourceFactory");
        resourcePackEpoch = new AtomicLong(epoch);
        VmaImage2D t = null, m = null, v = null;
        VulkanSampler ls = null, cs = null;
        VmaMappedBuffer si = null;
        ShaderObjectCompute ts = null, ms = null, vs = null;
        try {
            t = VmaImage2D.create(gpu, TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " transmittance");
            m = VmaImage2D.create(gpu, MULTISCATTER_WIDTH, MULTISCATTER_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " multiscatter");
            v = VmaImage2D.create(gpu, SKY_VIEW_WIDTH, SKY_VIEW_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " sky view");
            ls = VulkanSampler.linearClamp(gpu);
            cs = VulkanSampler.nearestClamp(gpu);
            si = createEmptyBuffer(gpu, SkyInputsData.BYTE_SIZE, "Minecraft sky inputs");
            ts = load(gpu, "transmittance.comp.spv");
            ms = load(gpu, "multiscatter.comp.spv");
            vs = load(gpu, "view.comp.spv");
        } catch (RuntimeException | Error failure) {
            closeAll(vs, ms, ts, si, cs, ls, v, m, t);
            throw failure;
        }
        transmittance = t; multiScatter = m; skyView = v;
        lutSampler = ls; celestialSampler = cs;
        skyInputs = si;
        transmittanceShader = ts; multiScatterShader = ms; skyViewShader = vs;
        resources = SharedResource.owned(
                new AutoCloseable[]{si, v, m, t, cs, ls}, SkyLutPass::closeAll);
    }

    public void invalidate(long epoch) { resourcePackEpoch.accumulateAndGet(epoch, Math::max); }

    @Override public void record(PassFrame frame) {
        boolean hasPriorGpuUse = initialized;
        if (!initialized) { initializeImages(frame.commandBuffer()); initialized = true; }
        MinecraftSkyFrame captured = frames.get();
        if (captured == null) return;
        if (hasPriorGpuUse) priorRayReadsToSkyWrites(frame.commandBuffer());
        SkyState state = gather(options.get(), captured.celestial());
        AtlasSnapshot snapshot = atlasSnapshot(captured.atlas());
        SkyInputsData inputs = skyInputs(state, snapshot);
        ensureBinding(snapshot);
        if (baked && Float.compare(bakedGroundAlbedo, state.groundAlbedo()) != 0) baked = false;
        if (!baked) {
            dispatch(transmittanceShader, frame, transmittance, inputs, skyInputsAddress());
            barrier(frame.commandBuffer());
            dispatch(multiScatterShader, frame, multiScatter, inputs, skyInputsAddress());
            barrier(frame.commandBuffer());
            bakedGroundAlbedo = state.groundAlbedo();
            baked = true;
        }
        dispatch(skyViewShader, frame, skyView, inputs, skyInputsAddress());
    }

    private void ensureBinding(AtlasSnapshot snapshot) {
        long epoch = resourcePackEpoch.get();
        if (atlas != null && sameBindingEpoch(atlas.get().image.vkImage(), atlas.get().epoch,
                snapshot.image().vkImage(), epoch)) return;
        SharedResource<AtlasEntry> replacement = AtlasEntry.create(
                gpu, snapshot.image(), snapshot.baseMipLevel(), snapshot.mipLevels(), epoch);
        BindingResources binding = null;
        ResourceOwner owner = null;
        boolean published = false;
        try {
            binding = createBinding(replacement.retain());
            owner = resourceFactory.create(binding::close);
            publishBinding(owner, environment, selector, binding.root.deviceRange().address().value());
            published = true;
        } catch (RuntimeException | Error failure) {
            if (owner != null) owner.close();
            throw failure;
        } finally {
            if (!published) {
                if (binding != null && owner == null) binding.close();
                replacement.close();
            }
        }
        ResourceOwner previousOwner = bindingOwner;
        SharedResource<AtlasEntry> previousAtlas = atlas;
        bindingOwner = owner;
        atlas = replacement;
        baked = false;
        if (previousOwner != null) previousOwner.close();
        if (previousAtlas != null) previousAtlas.close();
    }

    static void publishBinding(ResourceOwner owner,
                               EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                               MinecraftEnvironmentSelector selector, long address) {
        selector.select(new EnvironmentBinding<>(environment,
                MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA.data(address, owner.reference())));
    }

    static boolean sameBindingEpoch(long image, long epoch, long nextImage, long nextEpoch) {
        return image == nextImage && epoch == nextEpoch;
    }

    private BindingResources createBinding(SharedResource<AtlasEntry> atlasLease) {
        VmaMappedBuffer root = null;
        try {
            root = createBuffer(MinecraftEnvironmentBindingData.BYTE_SIZE, bytes ->
                    new MinecraftEnvironmentBindingData(
                            sampled(skyView.sampledIndex()), sampled(transmittance.sampledIndex()),
                            sampled(atlasLease.get().index()), sampler(lutSampler.index()),
                            sampler(celestialSampler.index()),
                            skyInputsAddress()).write(bytes));
            return new BindingResources(root, atlasLease, resources.retain());
        } catch (RuntimeException | Error failure) {
            closeAll(root);
            atlasLease.close();
            throw failure;
        }
    }

    private long skyInputsAddress() { return skyInputs.deviceRange().address().value(); }

    private VmaMappedBuffer createBuffer(int size, java.util.function.Consumer<ByteBuffer> writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(
                gpu, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Minecraft sky buffer");
        try {
            writer.accept(buffer.mapped().order(ByteOrder.LITTLE_ENDIAN));
            buffer.flush(0, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private static VmaMappedBuffer createEmptyBuffer(GpuDevice gpu, int size, String name) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(gpu, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, name);
        try {
            ByteBuffer bytes = buffer.mapped();
            for (int i = 0; i < size; i++) bytes.put(i, (byte) 0);
            buffer.flush(0, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private static MinecraftEnvironmentBindingData.SampledTexture2DIndex sampled(GpuDescriptorIndex.Resource index) {
        return new MinecraftEnvironmentBindingData.SampledTexture2DIndex(index.value());
    }
    private static MinecraftEnvironmentBindingData.SamplerIndex sampler(GpuDescriptorIndex.Sampler index) {
        return new MinecraftEnvironmentBindingData.SamplerIndex(index.value());
    }

    private void dispatch(ShaderObjectCompute shader, PassFrame frame, VmaImage2D destination,
                          SkyInputsData inputs, long skyInputsAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(SkyLutPushData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new SkyLutPushData(destination.storageIndex().value(),
                    new SkyLutPushData.SampledTexture2DIndex(transmittance.sampledIndex().value()),
                    new SkyLutPushData.SampledTexture2DIndex(multiScatter.sampledIndex().value()),
                    new SkyLutPushData.SamplerIndex(lutSampler.index().value()), pushInputs(inputs),
                    skyInputsAddress).write(push);
            shader.dispatch(frame.commandBuffer(), push, groups(destination.width()), groups(destination.height()), 1);
        }
    }

    private static SkyLutPushData.SkyInputs pushInputs(SkyInputsData v) {
        return new SkyLutPushData.SkyInputs(vec(v.celestial()), vec(v.skyLook0()), vec(v.skyLook1()),
                vec(v.skyLook2()), vec(v.skyLook3()), vec(v.sunUv()), vec(v.moonUv()));
    }
    private static SkyLutPushData.Float4 vec(SkyInputsData.Float4 v) {
        return new SkyLutPushData.Float4(v.x(), v.y(), v.z(), v.w());
    }
    static int groups(int extent) { return (extent + 7) / 8; }

    static SkyInputsData skyInputs(SkyState s, AtlasSnapshot a) {
        return new SkyInputsData(new SkyInputsData.Float4(s.sunAngleRadians(), s.moonAngleRadians(),
                s.starAngleRadians(), s.starBrightness()), new SkyInputsData.Float4(s.sunIlluminanceLux(),
                s.moonIlluminanceLux(), s.nightAirglowLuminance(), s.starLuminance()),
                new SkyInputsData.Float4(s.noonTiltRadians(), s.sunAngularRadiusRadians(),
                        s.moonAngularRadiusRadians(), s.moonPhaseFixedFraction()),
                new SkyInputsData.Float4(s.sunDiscHalfAngleRadians(), s.moonDiscHalfAngleRadians(),
                        s.viewerAltitudeKm(), s.moonPhaseIndex()),
                new SkyInputsData.Float4(s.groundAlbedo(), s.horizonSoftenRadians(), 0, 0),
                a.sunUv(), a.moonUv());
    }

    static SkyState gather(OptionValues options, MinecraftCelestialFrame captured) {
        float altitude = viewerAltitudeKm(captured.cameraY(), captured.seaLevel(),
                captured.metersPerSceneUnit());
        float r = (float) (Math.PI / 180.0);
        MinecraftLightingCalibration l = captured.lighting();
        return new SkyState(captured.sunAngleRadians(), captured.moonAngleRadians(),
                captured.starAngleRadians(), captured.starBrightness(), l.sunIlluminanceLux(),
                l.moonIlluminanceLux(), l.nightAirglowLuminanceCdM2(), l.starLuminanceCdM2(),
                options.get(SUN_NOON_SOUTH_TILT_DEGREES) * r, options.get(SUN_ANGULAR_RADIUS_DEGREES) * r,
                options.get(MOON_ANGULAR_RADIUS_DEGREES) * r, l.moonPhaseFixedFraction(),
                options.get(SUN_DISC_HALF_ANGLE_DEGREES) * r,
                options.get(MOON_DISC_HALF_ANGLE_DEGREES) * r, altitude,
                captured.moonPhaseIndex(), options.get(GROUND_ALBEDO),
                options.get(HORIZON_SOFTEN_DEGREES) * r);
    }

    static float viewerAltitudeKm(double cameraY, double seaLevel, double metersPerSceneUnit) {
        return Math.clamp((float) ((cameraY - seaLevel) * metersPerSceneUnit / 1000.0), 0, 99);
    }

    private static AtlasSnapshot atlasSnapshot(MinecraftSkyFrame.CelestialAtlas atlas) {
        return new AtlasSnapshot(atlas.image(), atlas.baseMipLevel(), atlas.mipLevels(),
                uv(atlas.sunUv()), uv(atlas.moonUv()));
    }
    private static SkyInputsData.Float4 uv(MinecraftSkyFrame.Uv uv) {
        return new SkyInputsData.Float4(uv.u0(), uv.v0(), uv.u1(), uv.v1());
    }

    private static ShaderObjectCompute load(GpuDevice gpu, String name) {
        try (InputStream input = SkyLutPass.class.getResourceAsStream(SHADER_ROOT + name)) {
            if (input == null) throw new IllegalStateException("missing sky shader " + name);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try { spirv.put(bytes).flip(); return ShaderObjectCompute.create(gpu, spirv, "main"); }
            finally { MemoryUtil.memFree(spirv); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void initializeImages(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<VmaImage2D> images = List.of(transmittance, multiScatter, skyView);
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.size(), stack);
            for (int i = 0; i < images.size(); i++) {
                VmaImage2D image = images.get(i);
                barriers.get(i).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                        .srcAccessMask(VK13.VK_ACCESS_2_NONE).dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                                | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                barriers.get(i).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            }
            VK14.vkCmdPipelineBarrier2(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }
    private static void barrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
            b.get(0).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                            | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            VK14.vkCmdPipelineBarrier2(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
        }
    }

    private void priorRayReadsToSkyWrites(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK14.vkCmdPipelineBarrier2(commandBuffer,
                    priorRayReadsToSkyWritesDependency(stack, skyView));
        }
    }

    private static VkDependencyInfo priorRayReadsToSkyWritesDependency(MemoryStack stack, VmaImage2D skyView) {
        VkMemoryBarrier2.Buffer buffers = VkMemoryBarrier2.calloc(1, stack);
        buffers.get(0).sType$Default()
                .srcStageMask(PRIOR_SKY_READ_STAGE)
                .srcAccessMask(PRIOR_SKY_READ_ACCESS)
                .dstStageMask(SKY_WRITE_STAGE)
                .dstAccessMask(SKY_WRITE_ACCESS);
        VkImageMemoryBarrier2.Buffer images = VkImageMemoryBarrier2.calloc(1, stack);
        images.get(0).sType$Default()
                .srcStageMask(PRIOR_SKY_READ_STAGE)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                .dstStageMask(SKY_WRITE_STAGE)
                .dstAccessMask(SKY_WRITE_ACCESS)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(skyView.image());
        images.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        return VkDependencyInfo.calloc(stack).sType$Default()
                .pMemoryBarriers(buffers).pImageMemoryBarriers(images);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (bindingOwner != null) bindingOwner.close();
        bindingOwner = null;
        if (atlas != null) try { atlas.close(); }
        catch (Throwable failure) { LOGGER.error("Sky atlas cleanup failed", failure); }
        atlas = null;
        closeAll(skyViewShader, multiScatterShader, transmittanceShader);
        resources.close();
    }
    private static void closeAll(AutoCloseable... resources) {
        for (AutoCloseable r : resources) if (r != null) try { r.close(); }
        catch (Exception e) { LOGGER.error("Sky resource cleanup failed", e); }
    }

    private record BindingResources(VmaMappedBuffer root, SharedResource<AtlasEntry> atlas,
                                    SharedResource<AutoCloseable[]> resources) implements AutoCloseable {
        @Override public void close() {
            Throwable failure = cleanup(null, root::close);
            failure = cleanup(failure, atlas::close);
            failure = cleanup(failure, resources::close);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    private static final class AtlasEntry {
        final CelestialAtlasImage image; final long epoch;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor;
        AtlasEntry(CelestialAtlasImage image, long epoch,
                   GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor) {
            this.image = image; this.epoch = epoch; this.descriptor = descriptor;
        }
        static SharedResource<AtlasEntry> create(GpuDevice gpu, CelestialAtlasImage image,
                                                 int baseMipLevel, int mipLevels, long epoch) {
            image.retainViews();
            GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                range = gpu.descriptorHeap().allocateResources(1);
                GpuDescriptorRange<GpuDescriptorIndex.Resource> allocated = range;
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(image.vkImage())
                        .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(baseMipLevel)
                        .levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT info = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                gpu.descriptorHeap().writer().writeResource(allocated, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(d -> d.pImage(info)));
                return SharedResource.owned(new AtlasEntry(image, epoch, allocated), AtlasEntry::close);
            } catch (RuntimeException | Error failure) {
                if (range != null) range.destroy();
                image.releaseViews();
                throw failure;
            }
        }
        void close() {
            Throwable failure = cleanup(null, descriptor::destroy);
            failure = cleanup(failure, image::releaseViews);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
        GpuDescriptorIndex.Resource index() { return descriptor.firstIndex(); }
    }

    record AtlasSnapshot(CelestialAtlasImage image, int baseMipLevel, int mipLevels,
                         SkyInputsData.Float4 sunUv, SkyInputsData.Float4 moonUv) { }
    record SkyState(float sunAngleRadians, float moonAngleRadians, float starAngleRadians, float starBrightness,
                    float sunIlluminanceLux, float moonIlluminanceLux, float nightAirglowLuminance,
                    float starLuminance, float noonTiltRadians, float sunAngularRadiusRadians,
                    float moonAngularRadiusRadians, float moonPhaseFixedFraction, float sunDiscHalfAngleRadians,
                    float moonDiscHalfAngleRadians, float viewerAltitudeKm, float moonPhaseIndex,
                    float groundAlbedo, float horizonSoftenRadians) { }

    private static Option<Float> option(String name, float min, float max, float value) {
        return Option.range("sky." + name, min, max, value).inGroup(GROUP);
    }
    private static Throwable cleanup(Throwable failure, Runnable action) {
        try { action.run(); }
        catch (Throwable cleanupFailure) {
            if (failure == null) return cleanupFailure;
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }
}
