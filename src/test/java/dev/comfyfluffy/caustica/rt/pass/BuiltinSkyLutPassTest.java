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
import dev.comfyfluffy.caustica.rt.gen.SkyLutPushData;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BuiltinSkyLutPassTest {
    private static final SkyFrame SKY = new SkyFrame(
            0.1f, 0.2f, 0.3f, 0.4f,
            100_000.0f, 0.2f, 0.003f, 1.5f,
            0.5f, 0.01f, 0.02f, 0.1f,
            0.03f, 0.04f, 1.25f, 2.0f, 0.3f, 0.05f);

    @Test
    void initializesStaticLutsOnceAndRecordsSkyViewEveryFrame() {
        BuiltinSkyLutPass pass = new BuiltinSkyLutPass();
        FakeResources resources = new FakeResources();
        pass.declareResources(resources);
        FakeContext context = new FakeContext(resources);
        var initialized = new LinkedHashSet<Identifier>();

        RenderPassManager.recordPass(pass, context, initialized);
        RenderPassManager.recordPass(pass, context, initialized);

        assertEquals(List.of("sky_lut_transmittance", "sky_lut_multiscatter",
                        "sky_lut_view", "sky_lut_view"),
                context.dispatches.stream().map(dispatch -> dispatch.program().id().getPath()).toList());
        assertEquals(List.of(32, 4, 24, 24),
                context.dispatches.stream().map(Dispatch::groupsX).toList());
        assertEquals(List.of(8, 4, 27, 27),
                context.dispatches.stream().map(Dispatch::groupsY).toList());
        assertEquals(0, context.dispatches.get(0).pushConstants().length);
        assertEquals(SkyLutPushData.BYTE_SIZE, context.dispatches.get(1).pushConstants().length);
        ByteBuffer push = ByteBuffer.wrap(context.dispatches.get(2).pushConstants())
                .order(ByteOrder.nativeOrder());
        assertEquals(SKY.sunAngleRadians(), push.getFloat(0));
        assertEquals(SKY.groundAlbedo(), push.getFloat(64));

        initialized.clear();
        RenderPassManager.recordPass(pass, context, initialized);
        assertEquals(7, context.dispatches.size());
    }

    @Test
    void publishesFixedEngineConsumedImages() {
        BuiltinSkyLutPass pass = new BuiltinSkyLutPass();
        FakeResources resources = new FakeResources();

        pass.declareResources(resources);

        assertEquals(new ImageSize.Fixed(256, 64),
                resources.images.get(resources.publications.get(EngineImage.SKY_TRANSMITTANCE_LUT)).size());
        assertEquals(new ImageSize.Fixed(192, 216),
                resources.images.get(resources.publications.get(EngineImage.SKY_VIEW_LUT)).size());
    }

    @Test
    void runtimeShadersCompileAndNoSkyLutSpirvIsPackaged(@TempDir Path cache) throws Exception {
        BuiltinSkyLutPass pass = new BuiltinSkyLutPass();
        FakeResources resources = new FakeResources();
        pass.declareResources(resources);

        for (ComputeProgram program : resources.programs.values()) {
            byte[] spirv = PassShaderCompiler.compile(cache, program).spirv();
            assertEquals(0x07230203,
                    ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        }
        assertNotNull(getClass().getResource("/caustica/shaders/world/sky_lut_view.slang"));
        assertNull(getClass().getResource(
                "/caustica/shaders/pipelines/sky_lut/view.comp.spv"));
        assertNull(getClass().getResource(
                "/caustica/shaders/pipelines/sky_lut/transmittance.comp.spv"));
        assertNull(getClass().getResource(
                "/caustica/shaders/pipelines/sky_lut/multiscatter.comp.spv"));
    }

    private static final class FakeResources implements ResourceRegistry {
        private final Map<ImageRef, ImageSpec> images = new LinkedHashMap<>();
        private final Map<EngineImage, ImageRef> publications = new EnumMap<>(EngineImage.class);
        private final Map<Identifier, ComputeProgram> programs = new LinkedHashMap<>();

        @Override
        public ImageRef engineImage(EngineImage image) {
            return new ImageRef(Identifier.fromNamespaceAndPath("test", image.name().toLowerCase()), 0);
        }

        @Override
        public ImageRef image(Identifier id, ImageFormat format, ImageSize size) {
            ImageRef image = new ImageRef(id, 0);
            images.put(image, new ImageSpec(format, size));
            return image;
        }

        @Override
        public ImagePyramid imagePyramid(Identifier id, ImageFormat format, ImageSize baseSize,
                                         int maxLevels, int minimumDimension) {
            return new ImagePyramid(id);
        }

        @Override
        public void publish(EngineImage slot, ImageRef image) {
            publications.put(slot, image);
        }

        @Override
        public ComputeProgram compute(ComputeProgram program) {
            programs.put(program.id(), program);
            return program;
        }
    }

    private static final class FakeContext implements PassContext {
        private final FakeResources resources;
        private final List<Dispatch> dispatches = new ArrayList<>();

        private FakeContext(FakeResources resources) {
            this.resources = resources;
        }

        @Override
        public SkyFrame skyFrame() {
            return SKY;
        }

        @Override
        public int levelCount(ImagePyramid pyramid) {
            return 1;
        }

        @Override
        public ImageExtent extent(ImageRef image) {
            ImageSize.Fixed size = (ImageSize.Fixed) resources.images.get(image).size();
            return new ImageExtent(size.width(), size.height());
        }

        @Override
        public void dispatch(ComputeProgram program, List<DispatchImage> images, byte[] pushConstants,
                             int groupCountX, int groupCountY, int groupCountZ) {
            dispatches.add(new Dispatch(program, List.copyOf(images), pushConstants.clone(),
                    groupCountX, groupCountY));
        }
    }

    private record ImageSpec(ImageFormat format, ImageSize size) {
    }

    private record Dispatch(ComputeProgram program, List<DispatchImage> images, byte[] pushConstants,
                            int groupsX, int groupsY) {
    }
}
