package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.nvidia.ngx.DlssSuperResolution;

import java.util.Objects;

/** Adapts the NVIDIA DLSS-SR implementation to the renderer's vendor-neutral upscaler boundary. */
public final class RtDlssSuperResolution implements RtUpscaler {
    private final DlssSuperResolution dlss;

    public RtDlssSuperResolution(DlssSuperResolution dlss) {
        this.dlss = Objects.requireNonNull(dlss, "dlss");
    }

    @Override
    public boolean configured() {
        return dlss.configured();
    }

    @Override
    public int configurationKey() {
        return dlss.configurationKey();
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        return dlss.queryOptimalRenderSize(displayWidth, displayHeight);
    }

    @Override
    public boolean record(Frame frame) {
        Extent extent = frame.extent();
        if (!dlss.ensureFeature(frame.commandBuffer(), extent.renderWidth(), extent.renderHeight(),
                extent.displayWidth(), extent.displayHeight())) {
            return false;
        }
        return dlss.evaluate(frame.commandBuffer(), frame.color(), frame.depth(), frame.motion(), frame.output(),
                extent.renderWidth(), extent.renderHeight(), extent.displayWidth(), extent.displayHeight(),
                -frame.jitterX(), -frame.jitterY(), frame.reset(), frame.preExposure());
    }

    @Override
    public void resetHistory() {
        dlss.resetHistory();
    }

    @Override
    public void destroyAfterDeviceIdle() {
        dlss.destroyAfterDeviceIdle();
    }
}
