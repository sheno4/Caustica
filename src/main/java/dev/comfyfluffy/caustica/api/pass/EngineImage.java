package dev.comfyfluffy.caustica.api.pass;

public enum EngineImage {
    RECONSTRUCTED_COLOR(false, ImageFormat.RGBA16_FLOAT, ImageSize.displayRelative(1)),
    EXPOSURE(false, ImageFormat.R32_FLOAT, ImageSize.fixed(1, 1)),
    BLOOM(true, ImageFormat.RGBA16_FLOAT, ImageSize.displayRelative(2)),
    SKY_VIEW_LUT(true, ImageFormat.RGBA16_FLOAT, ImageSize.fixed(192, 216)),
    SKY_TRANSMITTANCE_LUT(true, ImageFormat.RGBA16_FLOAT, ImageSize.fixed(256, 64));

    private final boolean passOutput;
    private final ImageFormat format;
    private final ImageSize size;

    EngineImage(boolean passOutput, ImageFormat format, ImageSize size) {
        this.passOutput = passOutput;
        this.format = format;
        this.size = size;
    }

    public boolean passOutput() {
        return passOutput;
    }

    public ImageFormat format() {
        return format;
    }

    public ImageSize size() {
        return size;
    }
}
