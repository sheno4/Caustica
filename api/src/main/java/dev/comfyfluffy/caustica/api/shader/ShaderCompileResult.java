package dev.comfyfluffy.caustica.api.shader;

import java.util.List;
import java.util.Objects;

/** Typed output of one shader compilation. Empty SPIR-V means compilation failed. */
public final class ShaderCompileResult {
    private final byte[] spirv;
    private final ShaderStage stage;
    private final ShaderHeapAbi heapAbi;
    private final List<ShaderDiagnostic> diagnostics;

    public ShaderCompileResult(
            byte[] spirv, ShaderStage stage, ShaderHeapAbi heapAbi, List<ShaderDiagnostic> diagnostics
    ) {
        this.spirv = Objects.requireNonNull(spirv, "spirv").clone();
        this.stage = Objects.requireNonNull(stage, "stage");
        this.heapAbi = heapAbi;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((this.spirv.length == 0) == (heapAbi != null)) {
            throw new IllegalArgumentException("successful compilation requires SPIR-V and a heap ABI");
        }
    }

    public boolean succeeded() { return spirv.length != 0; }
    public byte[] spirv() { return spirv.clone(); }
    public ShaderStage stage() { return stage; }
    public ShaderHeapAbi heapAbi() { return heapAbi; }
    public List<ShaderDiagnostic> diagnostics() { return diagnostics; }
}
