package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureStateData;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** GPU controller samples retain the frame and pre-exposure used by their originating submission. */
@Name("dev.comfyfluffy.caustica.Exposure")
@Label("Frame exposure")
@Category({"Caustica", "Presentation"})
@StackTrace(false)
@Enabled(false)
final class ExposureEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(ExposureEvent.class);

    long frameId;
    long observedFrameId;
    String mode;
    int resetSequence;
    float preExposure;
    float absoluteExposure;
    @Label("Residual exposure derived from controller state") float controllerResidualExposure;
    @Label("Scene luminance EV100") float evScene;
    @Label("Target log2 absolute exposure") float evTarget;
    @Label("Applied log2 absolute exposure") float evApplied;
    float clipLowFraction;
    float clipHighFraction;
    float environmentScale;
    float environmentFraction;
    float emissiveScale;
    float emissiveFraction;
    float curveCompensation;
    float effectiveSlope;

    static void record(long sourceFrame, long observedFrame, float preExposure, int resetSequence,
                       ExposureStateData state) {
        if (!TYPE.isEnabled()) return;
        ExposureEvent event = new ExposureEvent();
        event.frameId = sourceFrame;
        event.observedFrameId = observedFrame;
        event.mode = "auto";
        event.resetSequence = resetSequence;
        event.preExposure = preExposure;
        event.absoluteExposure = state.previous();
        event.controllerResidualExposure = state.previous() / preExposure;
        event.evScene = state.evScene();
        event.evTarget = state.evTarget();
        event.evApplied = state.evApplied();
        event.clipLowFraction = state.clipLowFrac();
        event.clipHighFraction = state.clipHighFrac();
        event.environmentScale = state.meteringEnvironmentScale();
        event.environmentFraction = state.meteringEnvironmentFrac();
        event.emissiveScale = state.meteringEmissiveScale();
        event.emissiveFraction = state.meteringEmissiveFrac();
        event.curveCompensation = state.curveCompensation();
        event.effectiveSlope = state.effectiveSlope();
        event.commit();
    }

    static void recordManual(long frameId, float preExposure, float absoluteExposure) {
        if (!TYPE.isEnabled()) return;
        ExposureEvent event = new ExposureEvent();
        event.frameId = frameId;
        event.observedFrameId = frameId;
        event.mode = "manual";
        event.preExposure = preExposure;
        event.absoluteExposure = absoluteExposure;
        event.controllerResidualExposure = absoluteExposure / preExposure;
        event.evScene = Float.NaN;
        event.evTarget = Float.NaN;
        event.evApplied = Float.NaN;
        event.commit();
    }
}
