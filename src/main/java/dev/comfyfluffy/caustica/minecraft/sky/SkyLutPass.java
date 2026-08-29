package dev.comfyfluffy.caustica.minecraft.sky;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.minecraft.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.sky.gen.*;
import dev.comfyfluffy.caustica.settings.*;
import dev.comfyfluffy.caustica.vulkan.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/** Owns the Overworld atmosphere LUTs and publishes immutable environment binding generations. */
public final class SkyLutPass implements Pass<PassFrame> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkyLutPass.class);
    public static final ResourceId ID = ResourceId.of("caustica", "sky_lut");
    private static final String SHADER_ROOT = "/caustica/shaders/pipelines/sky/";
    private static final Identifier SUN_SPRITE_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_SPRITE_IDS = moonSpriteIds();
    static final int TRANSMITTANCE_WIDTH = 256, TRANSMITTANCE_HEIGHT = 64;
    static final int MULTISCATTER_WIDTH = 32, MULTISCATTER_HEIGHT = 32;
    static final int SKY_VIEW_WIDTH = 192, SKY_VIEW_HEIGHT = 216;

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
    private final EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment;
    private final MinecraftEnvironmentSelector selector;
    private final VmaImage2D transmittance, multiScatter, skyView;
    private final VulkanSampler lutSampler, celestialSampler;
    private final ShaderObjectCompute transmittanceShader, multiScatterShader, skyViewShader;
    private final AtomicLong resourcePackEpoch;
    private AtlasEntry atlas;
    private boolean initialized, baked;
    private float bakedGroundAlbedo;
    private int liveBindings;

    public SkyLutPass(GpuDevice gpu, Supplier<OptionValues> options,
                      EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                      MinecraftEnvironmentSelector selector, long epoch) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.options = Objects.requireNonNull(options, "options");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.selector = Objects.requireNonNull(selector, "selector");
        resourcePackEpoch = new AtomicLong(epoch);
        VmaImage2D t = null, m = null, v = null;
        VulkanSampler ls = null, cs = null;
        ShaderObjectCompute ts = null, ms = null, vs = null;
        try {
            t = VmaImage2D.create(gpu, TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " transmittance");
            m = VmaImage2D.create(gpu, MULTISCATTER_WIDTH, MULTISCATTER_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " multiscatter");
            v = VmaImage2D.create(gpu, SKY_VIEW_WIDTH, SKY_VIEW_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " sky view");
            ls = VulkanSampler.linearClamp(gpu, ID + " LUT sampler");
            cs = VulkanSampler.nearestClamp(gpu, ID + " celestial sampler");
            ts = load(gpu, "transmittance.comp.spv");
            ms = load(gpu, "multiscatter.comp.spv");
            vs = load(gpu, "view.comp.spv");
        } catch (RuntimeException | Error failure) {
            closeAll(vs, ms, ts, cs, ls, v, m, t);
            throw failure;
        }
        transmittance = t; multiScatter = m; skyView = v;
        lutSampler = ls; celestialSampler = cs;
        transmittanceShader = ts; multiScatterShader = ms; skyViewShader = vs;
    }

    public void invalidate(long epoch) { resourcePackEpoch.accumulateAndGet(epoch, Math::max); }

    @Override public void record(PassFrame frame) {
        if (!initialized) { initializeImages(frame.commandBuffer()); initialized = true; }
        SkyState state = gather(options.get(), frame);
        AtlasSnapshot snapshot = celestialAtlas(state);
        if (snapshot == null) return;
        ensureAtlas(snapshot);
        SkyInputsData inputs = skyInputs(state, snapshot);
        if (baked && Float.compare(bakedGroundAlbedo, state.groundAlbedo()) != 0) baked = false;
        if (!baked) {
            dispatch(transmittanceShader, frame, transmittance, inputs);
            barrier(frame.commandBuffer());
            dispatch(multiScatterShader, frame, multiScatter, inputs);
            barrier(frame.commandBuffer());
            bakedGroundAlbedo = state.groundAlbedo();
            baked = true;
        }
        dispatch(skyViewShader, frame, skyView, inputs);
        BindingGeneration binding = createBinding(inputs, atlas.retain());
        try {
            selector.select(new EnvironmentBinding<>(environment,
                    MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA.data(binding.root.address), binding::retire));
            binding.published = true;
        } finally {
            if (!binding.published) binding.closeStrict();
        }
    }

    private void ensureAtlas(AtlasSnapshot snapshot) {
        long epoch = resourcePackEpoch.get();
        if (atlas != null && atlas.texture == snapshot.texture() && atlas.epoch == epoch) return;
        AtlasEntry replacement = AtlasEntry.create(
                gpu, snapshot.texture(), snapshot.baseMipLevel(), snapshot.mipLevels(), epoch);
        AtlasEntry previous = atlas;
        atlas = replacement;
        baked = false;
        if (previous != null) previous.release();
    }

    private BindingGeneration createBinding(SkyInputsData inputs, AtlasEntry atlasLease) {
        SkyBuffer inputBuffer = null, root = null;
        try {
            inputBuffer = SkyBuffer.create(gpu, SkyInputsData.BYTE_SIZE, inputs::write);
            SkyBuffer captured = inputBuffer;
            root = SkyBuffer.create(gpu, MinecraftEnvironmentBindingData.BYTE_SIZE, bytes ->
                    new MinecraftEnvironmentBindingData(
                            sampled(skyView.sampledIndex()), sampled(transmittance.sampledIndex()),
                            sampled(atlasLease.index()), sampler(lutSampler.index()),
                            sampler(celestialSampler.index()), captured.address).write(bytes));
            liveBindings++;
            return new BindingGeneration(root, inputBuffer, atlasLease);
        } catch (RuntimeException | Error failure) {
            closeAll(root, inputBuffer);
            atlasLease.release();
            throw failure;
        }
    }

    private static MinecraftEnvironmentBindingData.SampledTexture2DIndex sampled(GpuDescriptorIndex.Resource index) {
        return new MinecraftEnvironmentBindingData.SampledTexture2DIndex(index.value());
    }
    private static MinecraftEnvironmentBindingData.SamplerIndex sampler(GpuDescriptorIndex.Sampler index) {
        return new MinecraftEnvironmentBindingData.SamplerIndex(index.value());
    }

    private void dispatch(ShaderObjectCompute shader, PassFrame frame, VmaImage2D destination, SkyInputsData inputs) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(SkyLutPushData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new SkyLutPushData(destination.storageIndex().value(),
                    new SkyLutPushData.SampledTexture2DIndex(transmittance.sampledIndex().value()),
                    new SkyLutPushData.SampledTexture2DIndex(multiScatter.sampledIndex().value()),
                    new SkyLutPushData.SamplerIndex(lutSampler.index().value()), pushInputs(inputs)).write(push);
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

    private static SkyState gather(OptionValues options, PassFrame frame) {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        int sea = mc.level != null ? mc.level.getSeaLevel() : 0;
        float altitude = viewerAltitudeKm(frame.view().camera().y(), sea, frame.metersPerSceneUnit());
        float r = (float) (Math.PI / 180.0);
        MinecraftLightingCalibration l = MinecraftLightingCalibration.current();
        return new SkyState(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * r,
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * r,
                probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * r,
                probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial), l.sunIlluminanceLux(),
                l.moonIlluminanceLux(), l.nightAirglowLuminanceCdM2(), l.starLuminanceCdM2(),
                options.get(SUN_NOON_SOUTH_TILT_DEGREES) * r, options.get(SUN_ANGULAR_RADIUS_DEGREES) * r,
                options.get(MOON_ANGULAR_RADIUS_DEGREES) * r, l.moonPhaseFixedFraction(),
                options.get(SUN_DISC_HALF_ANGLE_DEGREES) * r,
                options.get(MOON_DISC_HALF_ANGLE_DEGREES) * r, altitude,
                probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(), options.get(GROUND_ALBEDO),
                options.get(HORIZON_SOFTEN_DEGREES) * r);
    }

    static float viewerAltitudeKm(double cameraY, double seaLevel, double metersPerSceneUnit) {
        return Math.clamp((float) ((cameraY - seaLevel) * metersPerSceneUnit / 1000.0), 0, 99);
    }

    private static AtlasSnapshot celestialAtlas(SkyState state) {
        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
            GpuTextureView raw = atlas.getTextureView();
            if (!(raw instanceof VulkanGpuTextureView view) || view.texture().getFormat() != GpuFormat.RGBA8_UNORM) return null;
            TextureAtlasSprite sun = atlas.getSprite(SUN_SPRITE_ID);
            int phase = Math.clamp((int) state.moonPhaseIndex(), 0, MOON_SPRITE_IDS.length - 1);
            TextureAtlasSprite moon = atlas.getSprite(MOON_SPRITE_IDS[phase]);
            return new AtlasSnapshot(view.texture(), view.baseMipLevel(), view.mipLevels(),
                    uv(sun), uv(moon));
        } catch (RuntimeException unavailable) { return null; }
    }
    private static SkyInputsData.Float4 uv(TextureAtlasSprite s) {
        return new SkyInputsData.Float4(s.getU0(), s.getV0(), s.getU1(), s.getV1());
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
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
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
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT);
            VK14.vkCmdPipelineBarrier2(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
        }
    }

    @Override public void close() {
        if (liveBindings != 0) LOGGER.error("Sky pass closed with {} live environment bindings", liveBindings);
        if (atlas != null) try { atlas.release(); }
        catch (Throwable failure) { LOGGER.error("Sky atlas cleanup failed", failure); }
        closeAll(skyViewShader, multiScatterShader, transmittanceShader, celestialSampler, lutSampler,
                skyView, multiScatter, transmittance);
    }
    private static void closeAll(AutoCloseable... resources) {
        for (AutoCloseable r : resources) if (r != null) try { r.close(); }
        catch (Exception e) { LOGGER.error("Sky resource cleanup failed", e); }
    }

    private final class BindingGeneration {
        final SkyBuffer root, skyInputs; final AtlasEntry atlas;
        boolean published, closed;
        BindingGeneration(SkyBuffer root, SkyBuffer skyInputs, AtlasEntry atlas) {
            this.root = root; this.skyInputs = skyInputs; this.atlas = atlas;
        }
        void closeStrict() {
            Throwable failure = release();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
        void retire() {
            Throwable failure = release();
            if (failure != null) LOGGER.error("Sky binding retirement failed", failure);
        }
        synchronized Throwable release() {
            if (closed) return null;
            closed = true;
            Throwable failure = cleanup(null, root::close);
            failure = cleanup(failure, skyInputs::close);
            failure = cleanup(failure, atlas::release);
            liveBindings--;
            return failure;
        }
    }

    private static final class AtlasEntry {
        final VulkanGpuTexture texture; final long epoch;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor;
        int references = 1;
        AtlasEntry(VulkanGpuTexture texture, long epoch,
                   GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor) {
            this.texture = texture; this.epoch = epoch; this.descriptor = descriptor;
        }
        static AtlasEntry create(GpuDevice gpu, VulkanGpuTexture texture,
                                 int baseMipLevel, int mipLevels, long epoch) {
            texture.addViews();
            GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                range = gpu.descriptorHeap().allocateResources(
                        1, "Minecraft celestials atlas epoch " + epoch);
                GpuDescriptorRange<GpuDescriptorIndex.Resource> allocated = range;
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(texture.vkImage())
                        .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(baseMipLevel)
                        .levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT info = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                gpu.descriptorHeap().writer().writeResource(allocated, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(d -> d.pImage(info)));
                return new AtlasEntry(texture, epoch, allocated);
            } catch (RuntimeException | Error failure) {
                if (range != null) range.destroy();
                texture.removeViews();
                throw failure;
            }
        }
        synchronized AtlasEntry retain() { references++; return this; }
        synchronized void release() {
            if (--references != 0) return;
            Throwable failure = cleanup(null, descriptor::destroy);
            failure = cleanup(failure, texture::removeViews);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
        GpuDescriptorIndex.Resource index() { return descriptor.firstIndex(); }
    }

    private static final class SkyBuffer implements AutoCloseable {
        final long allocator, buffer, allocation, address; boolean closed;
        SkyBuffer(long allocator, long buffer, long allocation, long address) {
            this.allocator = allocator; this.buffer = buffer; this.allocation = allocation; this.address = address;
        }
        static SkyBuffer create(GpuDevice gpu, int size, java.util.function.Consumer<ByteBuffer> writer) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                        .usage(VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo ai = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer outBuffer = stack.mallocLong(1); PointerBuffer outAllocation = stack.mallocPointer(1);
                VmaAllocationInfo outInfo = VmaAllocationInfo.calloc(stack);
                int result = Vma.vmaCreateBuffer(gpu.vmaAllocator(), info, ai, outBuffer, outAllocation, outInfo);
                if (result != VK_SUCCESS) throw new IllegalStateException("vmaCreateBuffer failed: " + result);
                long buffer = outBuffer.get(0), allocation = outAllocation.get(0);
                long address = vkGetBufferDeviceAddress(gpu.vk(),
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
                if (address == 0 || outInfo.pMappedData() == 0) {
                    Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, allocation);
                    throw new IllegalStateException("sky buffer is not mapped and device-addressable");
                }
                writer.accept(MemoryUtil.memByteBuffer(outInfo.pMappedData(), size).order(ByteOrder.LITTLE_ENDIAN));
                Vma.vmaFlushAllocation(gpu.vmaAllocator(), allocation, 0, size);
                return new SkyBuffer(gpu.vmaAllocator(), buffer, allocation, address);
            }
        }
        @Override public void close() { if (!closed) { closed = true; Vma.vmaDestroyBuffer(allocator, buffer, allocation); } }
    }

    record AtlasSnapshot(VulkanGpuTexture texture, int baseMipLevel, int mipLevels,
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
    private static Identifier[] moonSpriteIds() {
        MoonPhase[] phases = MoonPhase.values(); Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        return ids;
    }
}
