package dev.comfyfluffy.caustica.engine.frame;

import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.joml.Matrix4f;

import java.util.Objects;

/** Immutable engine state consumed while recording one rendered scene frame. */
public final class FrameSnapshot {
    private final SceneView view;
    private final Matrix4f projection;
    private final Matrix4f viewRotation;
    private final SceneOrigin sceneOrigin;
    private final boolean proceduralSurfaceAnimationEnabled;
    private final double timeSeconds;
    private final double metersPerWorldUnit;

    public FrameSnapshot(SceneView view, SceneOrigin sceneOrigin,
                         boolean proceduralSurfaceAnimationEnabled, double timeSeconds,
                         double metersPerWorldUnit) {
        this.view = Objects.requireNonNull(view, "view");
        Camera camera = view.camera();
        this.projection = new Matrix4f().set(camera.clipFromView());
        this.viewRotation = new Matrix4f().set(camera.viewFromSceneRotation());
        this.sceneOrigin = Objects.requireNonNull(sceneOrigin, "sceneOrigin");
        this.proceduralSurfaceAnimationEnabled = proceduralSurfaceAnimationEnabled;
        this.timeSeconds = timeSeconds;
        this.metersPerWorldUnit = metersPerWorldUnit;
    }

    public SceneView view() { return view; }

    /** Units and origin belong to the completed scene used by this frame. */
    public FrameSnapshot withSceneCoordinates(SceneOrigin origin, double metersPerUnit) {
        return sceneOrigin.equals(origin) && metersPerWorldUnit == metersPerUnit ? this : new FrameSnapshot(view, origin,
                proceduralSurfaceAnimationEnabled, timeSeconds, metersPerUnit);
    }

    public Matrix4f copyProjection() {
        return new Matrix4f(projection);
    }

    public Matrix4f copyViewRotation() {
        return new Matrix4f(viewRotation);
    }

    public Matrix4f copyProjectionTo(Matrix4f destination) {
        return Objects.requireNonNull(destination, "destination").set(projection);
    }

    public Matrix4f copyViewRotationTo(Matrix4f destination) {
        return Objects.requireNonNull(destination, "destination").set(viewRotation);
    }

    public double cameraX() {
        return view.camera().x();
    }

    public double cameraY() {
        return view.camera().y();
    }

    public double cameraZ() {
        return view.camera().z();
    }

    /** Scene origin used to keep this frame's GPU coordinates precise. */
    public SceneOrigin sceneOrigin() {
        return sceneOrigin;
    }

    public boolean proceduralSurfaceAnimationEnabled() {
        return proceduralSurfaceAnimationEnabled;
    }

    public double timeSeconds() {
        return timeSeconds;
    }

    public double metersPerWorldUnit() {
        return metersPerWorldUnit;
    }

}
