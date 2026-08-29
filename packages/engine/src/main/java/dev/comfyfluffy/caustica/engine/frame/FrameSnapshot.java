package dev.comfyfluffy.caustica.engine.frame;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.joml.Matrix4f;

import java.util.Objects;

/** Immutable engine state consumed while recording one rendered scene frame. */
public final class FrameSnapshot {
    /** Typed volume selected at the camera origin; a null snapshot field means vacuum. */
    public record InitialVolume<B, N>(VolumeId<B, N> volume, ShaderData<B> bindingData,
                                      ShaderData<N> instanceData) {
        public InitialVolume {
            Objects.requireNonNull(volume, "volume");
            Objects.requireNonNull(bindingData, "bindingData");
            Objects.requireNonNull(instanceData, "instanceData");
        }
    }

    private final SceneView view;
    private final Matrix4f projection;
    private final Matrix4f viewRotation;
    private final SceneOrigin sceneOrigin;
    private final InitialVolume<?, ?> initialVolume;
    private final boolean proceduralSurfaceAnimationEnabled;
    private final double timeSeconds;
    private final double metersPerWorldUnit;

    public FrameSnapshot(SceneView view, SceneOrigin sceneOrigin, InitialVolume<?, ?> initialVolume,
                         boolean proceduralSurfaceAnimationEnabled, double timeSeconds,
                         double metersPerWorldUnit) {
        this.view = Objects.requireNonNull(view, "view");
        Camera camera = view.camera();
        this.projection = new Matrix4f().set(camera.clipFromView());
        this.viewRotation = new Matrix4f().set(camera.viewFromSceneRotation());
        this.sceneOrigin = Objects.requireNonNull(sceneOrigin, "sceneOrigin");
        this.initialVolume = initialVolume;
        this.proceduralSurfaceAnimationEnabled = proceduralSurfaceAnimationEnabled;
        this.timeSeconds = timeSeconds;
        this.metersPerWorldUnit = metersPerWorldUnit;
    }

    public SceneView view() { return view; }

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

    /** Host-selected world origin used to keep this frame's GPU coordinates precise. */
    public SceneOrigin sceneOrigin() {
        return sceneOrigin;
    }

    public InitialVolume<?, ?> initialVolume() {
        return initialVolume;
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
