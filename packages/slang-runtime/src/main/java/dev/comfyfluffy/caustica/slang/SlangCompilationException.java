package dev.comfyfluffy.caustica.slang;

public final class SlangCompilationException extends RuntimeException {
    private final int result;
    private final String diagnostics;

    SlangCompilationException(String operation, int result, String diagnostics) {
        super(operation + " failed with Slang result 0x" + Integer.toHexString(result)
                + (diagnostics == null || diagnostics.isBlank() ? "" : ":\n" + diagnostics));
        this.result = result;
        this.diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public int result() {
        return result;
    }

    public String diagnostics() {
        return diagnostics;
    }
}
