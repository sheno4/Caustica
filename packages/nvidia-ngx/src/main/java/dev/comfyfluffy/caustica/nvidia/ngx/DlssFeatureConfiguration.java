package dev.comfyfluffy.caustica.nvidia.ngx;

/** Creation parameters retained with a live DLSS feature. */
record DlssFeatureConfiguration(int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                int quality, int preset) {
    boolean matches(int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                    int quality, int preset) {
        return this.renderWidth == renderWidth && this.renderHeight == renderHeight
                && this.displayWidth == displayWidth && this.displayHeight == displayHeight
                && this.quality == quality && this.preset == preset;
    }
}
