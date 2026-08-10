package dev.comfyfluffy.caustica.minecraft.sky;

import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.rt.gen.SkyInputsData;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SkyLutPassTest {
    private static final SkyLutPass.SkyState SKY = new SkyLutPass.SkyState(
            0.1f, 0.2f, 0.3f, 0.4f,
            100_000.0f, 0.2f, 0.003f, 1.5f,
            0.5f, 0.01f, 0.02f, 0.1f,
            0.03f, 0.04f, 1.25f, 2.0f, 0.3f, 0.05f);

    @Test
    void skyInputsMapSkyStateFieldsInDeclaredOrder() {
        // The same bytes go two places — the LUT bakes' push constant and the set-2 uniform buffer the
        // sky slot reads — so this pins the layout both consumers agree on.
        ByteBuffer push = ByteBuffer.wrap(SkyLutPass.pushConstants(new SkyLutPass().skyInputs(SKY)))
                .order(ByteOrder.nativeOrder());

        assertEquals(SkyInputsData.BYTE_SIZE, push.capacity());
        assertEquals(SKY.sunAngleRadians(), push.getFloat(0));
        assertEquals(SKY.moonAngleRadians(), push.getFloat(4));
        assertEquals(SKY.starAngleRadians(), push.getFloat(8));
        assertEquals(SKY.starBrightness(), push.getFloat(12));
        assertEquals(SKY.sunIlluminanceLux(), push.getFloat(16));
        assertEquals(SKY.noonTiltRadians(), push.getFloat(32));
        assertEquals(SKY.sunDiscHalfAngleRadians(), push.getFloat(48));
        assertEquals(SKY.groundAlbedo(), push.getFloat(64));
        assertEquals(SKY.horizonSoftenRadians(), push.getFloat(68));
    }

    @Test
    void celestialSpriteRectsDefaultToTheFullAtlasBeforeItIsStitched() {
        // Until the celestials atlas is stitched the rects stay full-range, so the discs sample a
        // defined texel rather than reading an unbound descriptor.
        ByteBuffer push = ByteBuffer.wrap(SkyLutPass.pushConstants(new SkyLutPass().skyInputs(SKY)))
                .order(ByteOrder.nativeOrder());

        assertEquals(0.0f, push.getFloat(80));
        assertEquals(0.0f, push.getFloat(84));
        assertEquals(1.0f, push.getFloat(88));
        assertEquals(1.0f, push.getFloat(92));
        assertEquals(0.0f, push.getFloat(96));
        assertEquals(1.0f, push.getFloat(104));
    }

    @Test
    void lutDimensionsMatchTheSlangSourceTheyBakeAgainst() {
        // sky.slang's TRANSMITTANCE_LUT_W/H and sky_lut_view.slang's SKY_VIEW_LUT_W / _TOTAL_H.
        assertEquals(256, SkyLutPass.TRANSMITTANCE_WIDTH);
        assertEquals(64, SkyLutPass.TRANSMITTANCE_HEIGHT);
        assertEquals(192, SkyLutPass.SKY_VIEW_WIDTH);
        assertEquals(216, SkyLutPass.SKY_VIEW_HEIGHT);
    }

    @Test
    void runtimeShadersCompileAndNoSkyLutSpirvIsPackaged(@TempDir Path cache) throws Exception {
        ShaderSource source = ShaderSource.classpath("/caustica/shaders/minecraft", "sky");

        compileAndValidate(cache, source, "caustica_minecraft_sky_lut_transmittance",
                SkyLutPass.TRANSMITTANCE_BINDINGS, 0);
        compileAndValidate(cache, source, "caustica_minecraft_sky_lut_multiscatter",
                SkyLutPass.SCATTER_BINDINGS, SkyInputsData.BYTE_SIZE);
        compileAndValidate(cache, source, "caustica_minecraft_sky_lut_view",
                SkyLutPass.SCATTER_BINDINGS, SkyInputsData.BYTE_SIZE);

        assertNotNull(getClass().getResource(
                "/caustica/shaders/minecraft/sky/caustica_minecraft_sky_lut_view.slang"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/sky_lut/view.comp.spv"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/sky_lut/transmittance.comp.spv"));
        assertNull(getClass().getResource("/caustica/shaders/pipelines/sky_lut/multiscatter.comp.spv"));
    }

    private static void compileAndValidate(Path cache, ShaderSource source, String module,
                                           java.util.List<ComputeDispatch.Binding> bindings,
                                           int pushConstantBytes) throws Exception {
        ResourceId id = ResourceId.of("caustica", module);
        PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(cache, id, source, module, "main");
        assertEquals(0x07230203, ByteBuffer.wrap(compiled.spirv()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        PassShaderCompiler.validateBindings(id, compiled.reflectionJson(), bindings, pushConstantBytes,
                "main", 8, 8, 1);
    }
}
