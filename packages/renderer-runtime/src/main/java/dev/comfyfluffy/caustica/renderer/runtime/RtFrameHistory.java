package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.renderer.runtime.pipeline.RtJitter;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Only submitted frames become temporal predecessors. Capturing or abandoning a frame changes nothing. */
final class RtFrameHistory {
    private RtFrameInput previous;
    private long samples;

    RtFrameInput capture(FrameSnapshot snapshot, long number, long nanos, TraceExtent extent,
                         DenoiserRoute route, float preExposure, float jitterSignX, float jitterSignY) {
        Matrix4f projection = snapshot.copyProjection();
        Matrix4f rotation = snapshot.copyViewRotation();
        Matrix4f projectionView = new Matrix4f(projection).mul(rotation);
        boolean continuous = previous != null && previous.number() + 1 == number
                && previous.snapshot().view().entryScene() == snapshot.view().entryScene()
                && previous.snapshot().sceneOrigin().equals(snapshot.sceneOrigin())
                && previous.snapshot().metersPerWorldUnit() == snapshot.metersPerWorldUnit()
                && previous.extent().equals(extent) && previous.route() == route
                && !cameraCut(snapshot, projection, rotation);
        Float3 delta = continuous ? new Float3(
                (float) (snapshot.cameraX() - previous.snapshot().cameraX()),
                (float) (snapshot.cameraY() - previous.snapshot().cameraY()),
                (float) (snapshot.cameraZ() - previous.snapshot().cameraZ())) : new Float3(0, 0, 0);
        var jitter = route == DenoiserRoute.RAW ? new RtJitter.Sample(0, 0)
                : RtJitter.sample(samples, extent.renderWidth(), extent.displayWidth());
        float jitterX = jitter.x() * jitterSignX;
        float jitterY = jitter.y() * jitterSignY;
        float time = (float) (snapshot.timeSeconds() % 3600.0);
        float priorTime = continuous ? (float) (previous.snapshot().timeSeconds() % 3600.0) : time;
        if (time - priorTime < 0 || time - priorTime > .25f) priorTime = time;
        return new RtFrameInput(snapshot, number, nanos, extent, route, jitterX, jitterY, preExposure,
                continuous, projection, rotation, projectionView,
                continuous ? previous.projectionView() : projectionView,
                continuous ? previous.viewRotation() : rotation,
                continuous ? previous.projection() : projection,
                new Float3(snapshot.sceneOrigin().relativeX(snapshot.cameraX()),
                        snapshot.sceneOrigin().relativeY(snapshot.cameraY()),
                        snapshot.sceneOrigin().relativeZ(snapshot.cameraZ())), delta,
                continuous ? previous.jitterX() : jitterX, continuous ? previous.jitterY() : jitterY,
                continuous ? (nanos - previous.nanos()) * 1.0e-6f : 0, priorTime);
    }

    void submitted(RtFrameInput frame) {
        previous = frame;
        if (frame.route() != DenoiserRoute.RAW) samples++;
    }

    boolean changesScene(FrameSnapshot snapshot) {
        return previous == null || previous.snapshot().view().entryScene() != snapshot.view().entryScene();
    }

    void reset() { previous = null; }

    private boolean cameraCut(FrameSnapshot snapshot, Matrix4fc projection, Matrix4fc rotation) {
        Matrix4fc oldRotation = previous.viewRotation();
        float forwardDot = rotation.m02() * oldRotation.m02() + rotation.m12() * oldRotation.m12()
                + rotation.m22() * oldRotation.m22();
        return forwardDot < 0.70710677f
                || relativeDifference(projection.m00(), previous.projection().m00()) > .1f
                || relativeDifference(projection.m11(), previous.projection().m11()) > .1f
                || distanceSquared(snapshot, previous.snapshot()) * snapshot.metersPerWorldUnit()
                        * snapshot.metersPerWorldUnit() > 64;
    }

    private static double distanceSquared(FrameSnapshot a, FrameSnapshot b) {
        double x = a.cameraX() - b.cameraX(), y = a.cameraY() - b.cameraY(), z = a.cameraZ() - b.cameraZ();
        return x * x + y * y + z * z;
    }

    private static float relativeDifference(float a, float b) {
        return Math.abs(a - b) / Math.max(Math.max(Math.abs(a), Math.abs(b)), 1.0e-6f);
    }
}
