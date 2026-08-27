package dev.comfyfluffy.caustica.api.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCompileResultTest {
    @Test
    void successRequiresSpirvAndHeapAbiTogether() {
        ShaderHeapAbi abi = new ShaderHeapAbi(ShaderHeapAbi.CURRENT_VERSION, 16, true, true);
        assertTrue(new ShaderCompileResult(new byte[] {3, 2, 35, 7}, ShaderStage.COMPUTE, abi, List.of()).succeeded());
        assertFalse(new ShaderCompileResult(new byte[0], ShaderStage.COMPUTE, null, List.of()).succeeded());
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderCompileResult(new byte[] {1}, ShaderStage.COMPUTE, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderCompileResult(new byte[0], ShaderStage.COMPUTE, abi, List.of()));
    }

    @Test
    void spirvIsDefensivelyCopied() {
        byte[] source = {1, 2, 3, 4};
        ShaderCompileResult result = new ShaderCompileResult(source, ShaderStage.FRAGMENT,
                new ShaderHeapAbi(ShaderHeapAbi.CURRENT_VERSION, 0, false, false), List.of());
        source[0] = 9;
        byte[] returned = result.spirv();
        returned[1] = 9;
        assertTrue(result.spirv()[0] == 1 && result.spirv()[1] == 2);
    }
}
