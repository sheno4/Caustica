package dev.comfyfluffy.caustica.api.pass;

public enum EngineImage {
    RECONSTRUCTED_COLOR(false),
    EXPOSURE(false),
    BLOOM(true);

    private final boolean passOutput;

    EngineImage(boolean passOutput) {
        this.passOutput = passOutput;
    }

    public boolean passOutput() {
        return passOutput;
    }
}
