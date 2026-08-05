package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.pass.ComputeProgram;
import dev.comfyfluffy.caustica.api.pass.DispatchImage;
import dev.comfyfluffy.caustica.api.pass.EngineImage;
import dev.comfyfluffy.caustica.api.pass.ImageExtent;
import dev.comfyfluffy.caustica.api.pass.ImageFormat;
import dev.comfyfluffy.caustica.api.pass.ImagePyramid;
import dev.comfyfluffy.caustica.api.pass.ImageRef;
import dev.comfyfluffy.caustica.api.pass.ImageSize;
import dev.comfyfluffy.caustica.api.pass.PassContext;
import dev.comfyfluffy.caustica.api.pass.ResourceRegistry;
import dev.comfyfluffy.caustica.api.pass.SkyFrame;
import dev.comfyfluffy.caustica.rt.RtLookPackage;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuiltinBloomPassTest {
    @Test
    void recordsAnImperativeDownsampleAndUpsamplePyramid() {
        BuiltinBloomPass pass = new BuiltinBloomPass(new RtLookPackage.Bloom(0.2f, 1.5f, 0.4f, 1.25f, 4));
        FakeResources resources = new FakeResources();
        pass.declareResources(resources);
        FakeContext context = new FakeContext(resources.pyramid, 4, 960, 540);

        pass.record(context);

        assertEquals(7, context.dispatches.size());
        assertEquals(List.of(0, 1, 1, 1, 2, 2, 2),
                context.dispatches.stream().map(Dispatch::mode).toList());
        assertEquals(List.of(120, 60, 30, 15, 30, 60, 120),
                context.dispatches.stream().map(Dispatch::groupsX).toList());
        assertEquals(List.of(68, 34, 17, 9, 17, 34, 68),
                context.dispatches.stream().map(Dispatch::groupsY).toList());
        for (Dispatch dispatch : context.dispatches) {
            assertEquals(List.of("dstImage", "srcImage", "exposureImage"),
                    dispatch.images().stream().map(DispatchImage::binding).toList());
        }
        assertEquals(resources.pyramid.level(0), resources.publications.get(EngineImage.BLOOM));
    }

    @Test
    void runtimeShaderCompilesAndBuildDoesNotPackageABloomSpirv(@TempDir Path cache) throws Exception {
        BuiltinBloomPass pass = new BuiltinBloomPass(new RtLookPackage.Bloom(0.2f, 1.5f, 0.4f, 1.25f, 4));
        FakeResources resources = new FakeResources();
        pass.declareResources(resources);

        PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(cache, resources.program);

        assertEquals(0x07230203, ByteBuffer.wrap(compiled.spirv())
                .order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(Files.isRegularFile(cache.resolve("caustica/bloom_compute/caustica_bloom.slang")));
        assertNotNull(getClass().getResource(
                "/caustica/shaders/passes/bloom/caustica_bloom.slang"));
        assertNull(getClass().getResource(
                "/caustica/shaders/pipelines/bloom/main.comp.spv"));
    }

    @Test
    void pyramidSizingMatchesThePreviousBloomExtentPolicy() {
        assertEquals(8, RenderPassManager.levelCount(1920, 1080, 8, 8));
        assertEquals(4, RenderPassManager.levelCount(64, 64, 8, 8));
        assertEquals(1, RenderPassManager.levelCount(8, 64, 8, 8));
        assertEquals(4, RenderPassManager.levelCount(1920, 1080, 4, 8));
    }

    private static final class FakeResources implements ResourceRegistry {
        private final Map<EngineImage, ImageRef> engine = new EnumMap<>(EngineImage.class);
        private final Map<EngineImage, ImageRef> publications = new EnumMap<>(EngineImage.class);
        private ImagePyramid pyramid;
        private ComputeProgram program;

        private FakeResources() {
            for (EngineImage image : EngineImage.values()) {
                engine.put(image, new ImageRef(Identifier.fromNamespaceAndPath(
                        "test", image.name().toLowerCase(java.util.Locale.ROOT)), 0));
            }
        }

        @Override
        public ImageRef engineImage(EngineImage image) {
            return engine.get(image);
        }

        @Override
        public ImageRef image(Identifier id, ImageFormat format, ImageSize size) {
            return new ImageRef(id, 0);
        }

        @Override
        public ImagePyramid imagePyramid(Identifier id, ImageFormat format, ImageSize baseSize,
                                         int maxLevels, int minimumDimension) {
            pyramid = new ImagePyramid(id);
            return pyramid;
        }

        @Override
        public void publish(EngineImage slot, ImageRef image) {
            publications.put(slot, image);
        }

        @Override
        public ComputeProgram compute(ComputeProgram declared) {
            program = declared;
            return declared;
        }
    }

    private static final class FakeContext implements PassContext {
        private final ImagePyramid pyramid;
        private final int levels;
        private final int baseWidth;
        private final int baseHeight;
        private final List<Dispatch> dispatches = new ArrayList<>();

        private FakeContext(ImagePyramid pyramid, int levels, int baseWidth, int baseHeight) {
            this.pyramid = pyramid;
            this.levels = levels;
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
        }

        @Override
        public SkyFrame skyFrame() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int levelCount(ImagePyramid requested) {
            assertEquals(pyramid, requested);
            return levels;
        }

        @Override
        public ImageExtent extent(ImageRef image) {
            return new ImageExtent(Math.max(1, baseWidth >> image.level()),
                    Math.max(1, baseHeight >> image.level()));
        }

        @Override
        public void dispatch(ComputeProgram program, List<DispatchImage> images, byte[] pushConstants,
                             int groupCountX, int groupCountY, int groupCountZ) {
            int mode = ByteBuffer.wrap(pushConstants).order(ByteOrder.nativeOrder()).getInt(0);
            dispatches.add(new Dispatch(mode, images, groupCountX, groupCountY));
        }
    }

    private record Dispatch(int mode, List<DispatchImage> images, int groupsX, int groupsY) {
    }
}
