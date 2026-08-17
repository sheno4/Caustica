package dev.comfyfluffy.caustica.engine.frame;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** Immutable host-neutral state consumed while recording one rendered scene frame. */
public final class FrameSnapshot {
    /** Linear ACEScg colour used when the camera starts inside a participating medium. */
    public record LinearRgb(float red, float green, float blue) {
    }

    /** Named material and source colour for the volume containing the camera; null means vacuum. */
    public record CameraMedium(MaterialHandle material, LinearRgb sourceColor) {
        public CameraMedium {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(sourceColor, "sourceColor");
        }
    }

    private final Matrix4f projection;
    private final Matrix4f viewRotation;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final SceneOrigin sceneOrigin;
    private final CameraMedium cameraMedium;
    private final boolean proceduralSurfaceAnimationEnabled;
    private final double timeSeconds;
    private final double metersPerWorldUnit;
    private final long sceneId;

    public FrameSnapshot(Matrix4fc projection, Matrix4fc viewRotation,
                         double cameraX, double cameraY, double cameraZ,
                         SceneOrigin sceneOrigin,
                         CameraMedium cameraMedium, boolean proceduralSurfaceAnimationEnabled,
                         double timeSeconds, double metersPerWorldUnit, long sceneId) {
        this.projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
        this.viewRotation = new Matrix4f(Objects.requireNonNull(viewRotation, "viewRotation"));
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.sceneOrigin = Objects.requireNonNull(sceneOrigin, "sceneOrigin");
        this.cameraMedium = cameraMedium;
        this.proceduralSurfaceAnimationEnabled = proceduralSurfaceAnimationEnabled;
        this.timeSeconds = timeSeconds;
        this.metersPerWorldUnit = metersPerWorldUnit;
        this.sceneId = sceneId;
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
        return cameraX;
    }

    public double cameraY() {
        return cameraY;
    }

    public double cameraZ() {
        return cameraZ;
    }

    /** Host-selected world origin used to keep this frame's GPU coordinates precise. */
    public SceneOrigin sceneOrigin() {
        return sceneOrigin;
    }

    public CameraMedium cameraMedium() {
        return cameraMedium;
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

    /** Adapter-defined identity which changes whenever the host replaces the rendered scene. */
    public long sceneId() {
        return sceneId;
    }

}
