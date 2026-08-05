package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.ComputeBinding;
import dev.comfyfluffy.caustica.api.pass.ComputeProgram;
import dev.comfyfluffy.caustica.api.pass.DispatchImage;
import dev.comfyfluffy.caustica.api.pass.EngineImage;
import dev.comfyfluffy.caustica.api.pass.ImageExtent;
import dev.comfyfluffy.caustica.api.pass.ImageFormat;
import dev.comfyfluffy.caustica.api.pass.ImagePyramid;
import dev.comfyfluffy.caustica.api.pass.ImageRef;
import dev.comfyfluffy.caustica.api.pass.ImageSize;
import dev.comfyfluffy.caustica.api.pass.PassContext;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.pass.ResourceRegistry;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Owns declared pass resources, compute pipelines, descriptor state, barriers, and recording order. */
public final class RenderPassManager {
    private final RtContext ctx;
    private final List<CausticaRenderPass> passes;
    private final Declaration declaration = new Declaration();
    private final Map<Identifier, ComputePassPipeline> pipelines = new LinkedHashMap<>();
    private final Map<Identifier, RtImage[]> images = new LinkedHashMap<>();
    private final Map<EngineImage, RtImage> externalImages = new EnumMap<>(EngineImage.class);
    private final Map<EngineImage, ImageRef> engineRefs = new EnumMap<>(EngineImage.class);
    private final long sampler;
    private int displayWidth;
    private int displayHeight;

    private RenderPassManager(RtContext ctx, List<CausticaRenderPass> passes, long sampler) {
        this.ctx = ctx;
        this.passes = passes;
        this.sampler = sampler;
        for (EngineImage image : EngineImage.values()) {
            engineRefs.put(image, new ImageRef(Identifier.fromNamespaceAndPath(
                    "caustica", "engine/" + image.name().toLowerCase(java.util.Locale.ROOT)), 0));
        }
    }

    public static RenderPassManager create(RtContext ctx,
                                           Map<Identifier, CausticaRenderPass> registered) throws IOException {
        List<CausticaRenderPass> passes = registered.values().stream()
                .sorted(Comparator.comparing(pass -> pass.id().toString())).toList();
        long sampler = createSampler(ctx);
        RenderPassManager manager = new RenderPassManager(ctx, passes, sampler);
        try {
            for (CausticaRenderPass pass : passes) {
                pass.declareResources(manager.declaration);
            }
            Path cache = FabricLoader.getInstance().getGameDir()
                    .resolve("caustica-shaders").resolve("passes");
            for (ComputeProgram program : manager.declaration.programs.values()) {
                PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(cache, program);
                manager.pipelines.put(program.id(),
                        ComputePassPipeline.create(ctx, program, compiled.spirv(), sampler));
            }
            return manager;
        } catch (IOException | RuntimeException e) {
            manager.destroy();
            throw e;
        }
    }

    public void resize(int width, int height) {
        if (displayWidth == width && displayHeight == height && !images.isEmpty()) {
            return;
        }
        destroyImages();
        displayWidth = width;
        displayHeight = height;
        for (PyramidSpec spec : declaration.pyramids.values()) {
            int baseWidth = resolveWidth(spec.size(), width);
            int baseHeight = resolveHeight(spec.size(), height);
            int levels = levelCount(baseWidth, baseHeight, spec.maxLevels(), spec.minimumDimension());
            RtImage[] allocated = new RtImage[levels];
            int levelWidth = baseWidth;
            int levelHeight = baseHeight;
            for (int level = 0; level < levels; level++) {
                allocated[level] = ctx.createStorageImage(levelWidth, levelHeight,
                        vkFormat(spec.format()), spec.id() + " level " + level + " "
                                + levelWidth + "x" + levelHeight);
                levelWidth = Math.max(1, levelWidth / 2);
                levelHeight = Math.max(1, levelHeight / 2);
            }
            images.put(spec.id(), allocated);
        }
    }

    public void setExternalImage(EngineImage slot, RtImage image) {
        externalImages.put(slot, image);
    }

    public RtImage image(EngineImage slot) {
        return resolve(engineRefs.get(slot));
    }

    public boolean hasImage(EngineImage slot) {
        ImageRef publication = declaration.publications.get(slot);
        return publication != null && images.containsKey(publication.id());
    }

    public int levelCount(EngineImage slot) {
        ImageRef publication = declaration.publications.get(slot);
        return images.get(publication.id()).length;
    }

    public long sampler() {
        return sampler;
    }

    public void record(RenderStage stage, VkCommandBuffer commandBuffer) {
        for (ComputePassPipeline pipeline : pipelines.values()) {
            pipeline.beginFrame();
        }
        RecordingContext context = new RecordingContext(commandBuffer);
        for (CausticaRenderPass pass : passes) {
            if (pass.stage() != stage) {
                continue;
            }
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, commandBuffer, pass.id().toString())) {
                pass.record(context);
            }
        }
    }

    public void destroy() {
        destroyImages();
        for (ComputePassPipeline pipeline : pipelines.values()) {
            pipeline.destroy();
        }
        pipelines.clear();
        VK10.vkDestroySampler(ctx.vk(), sampler, null);
    }

    private RtImage resolve(ImageRef reference) {
        for (Map.Entry<EngineImage, ImageRef> entry : engineRefs.entrySet()) {
            if (!entry.getValue().id().equals(reference.id())) {
                continue;
            }
            ImageRef publication = declaration.publications.get(entry.getKey());
            return publication != null ? resolve(publication) : externalImages.get(entry.getKey());
        }
        return images.get(reference.id())[reference.level()];
    }

    private void destroyImages() {
        for (RtImage[] pyramid : images.values()) {
            for (RtImage image : pyramid) {
                image.destroy();
            }
        }
        images.clear();
    }

    static int levelCount(int width, int height, int maximum, int minimumDimension) {
        int levels = 1;
        while (levels < maximum && width > minimumDimension && height > minimumDimension) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            levels++;
        }
        return levels;
    }

    private static int resolveWidth(ImageSize size, int displayWidth) {
        return switch (size) {
            case ImageSize.DisplayRelative relative ->
                    Math.max(1, (displayWidth + relative.divisor() - 1) / relative.divisor());
            case ImageSize.Fixed fixed -> fixed.width();
        };
    }

    private static int resolveHeight(ImageSize size, int displayHeight) {
        return switch (size) {
            case ImageSize.DisplayRelative relative ->
                    Math.max(1, (displayHeight + relative.divisor() - 1) / relative.divisor());
            case ImageSize.Fixed fixed -> fixed.height();
        };
    }

    private static int vkFormat(ImageFormat format) {
        return switch (format) {
            case RGBA16_FLOAT -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case RG16_FLOAT -> VK10.VK_FORMAT_R16G16_SFLOAT;
            case R32_FLOAT -> VK10.VK_FORMAT_R32_SFLOAT;
            case RGBA8_UNORM -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
        };
    }

    private static long createSampler(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateSampler(ctx.vk(), info, null, handle),
                    "vkCreateSampler(render passes)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, handle.get(0),
                    "render-pass linear clamp sampler");
            return handle.get(0);
        }
    }

    private final class Declaration implements ResourceRegistry {
        private final Map<Identifier, PyramidSpec> pyramids = new LinkedHashMap<>();
        private final Map<EngineImage, ImageRef> publications = new EnumMap<>(EngineImage.class);
        private final Map<Identifier, ComputeProgram> programs = new LinkedHashMap<>();

        @Override
        public ImageRef engineImage(EngineImage image) {
            return engineRefs.get(image);
        }

        @Override
        public ImagePyramid imagePyramid(Identifier id, ImageFormat format, ImageSize baseSize,
                                         int maxLevels, int minimumDimension) {
            PyramidSpec spec = new PyramidSpec(id, format, baseSize, maxLevels, minimumDimension);
            if (pyramids.putIfAbsent(id, spec) != null) {
                throw new IllegalStateException("duplicate render-pass image " + id);
            }
            return new ImagePyramid(id);
        }

        @Override
        public void publish(EngineImage slot, ImageRef image) {
            if (!slot.passOutput()) {
                throw new IllegalArgumentException(slot + " is engine-produced and cannot be published by a pass");
            }
            if (publications.putIfAbsent(slot, image) != null) {
                throw new IllegalStateException("multiple render passes publish " + slot);
            }
        }

        @Override
        public ComputeProgram compute(ComputeProgram program) {
            if (programs.putIfAbsent(program.id(), program) != null) {
                throw new IllegalStateException("duplicate render-pass compute program " + program.id());
            }
            return program;
        }
    }

    private final class RecordingContext implements PassContext {
        private final VkCommandBuffer commandBuffer;

        private RecordingContext(VkCommandBuffer commandBuffer) {
            this.commandBuffer = commandBuffer;
        }

        @Override
        public int levelCount(ImagePyramid pyramid) {
            return images.get(pyramid.id()).length;
        }

        @Override
        public ImageExtent extent(ImageRef image) {
            RtImage resolved = resolve(image);
            return new ImageExtent(resolved.width, resolved.height);
        }

        @Override
        public void dispatch(ComputeProgram program, List<DispatchImage> dispatchedImages,
                             byte[] pushConstants, int groupCountX, int groupCountY, int groupCountZ) {
            Map<String, ImageRef> byName = new HashMap<>();
            for (DispatchImage image : dispatchedImages) {
                byName.put(image.binding(), image.image());
            }
            List<ComputeBinding> bindings = program.bindings();
            RtImage[] resolved = new RtImage[bindings.size()];
            for (int index = 0; index < bindings.size(); index++) {
                resolved[index] = resolve(byName.get(bindings.get(index).name()));
            }
            pipelines.get(program.id()).dispatch(commandBuffer, resolved, pushConstants,
                    groupCountX, groupCountY, groupCountZ);
        }
    }

    private record PyramidSpec(Identifier id, ImageFormat format, ImageSize size,
                               int maxLevels, int minimumDimension) {
    }
}
