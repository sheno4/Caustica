package dev.comfyfluffy.caustica.engine.frame;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;
import java.util.List;

/** Immutable host-neutral state consumed while recording one rendered scene frame. */
public final class FrameSnapshot {
    /** Linear ACEScg colour used when the camera starts inside a participating medium. */
    public record LinearRgb(float red, float green, float blue) {
    }

    private final Matrix4f projection;
    private final Matrix4f viewRotation;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final boolean cameraInMedium;
    private final LinearRgb cameraMedium;
    private final double timeSeconds;
    private final double metersPerWorldUnit;
    private final long sceneId;
    private final List<DamageOverlay> damageOverlays;

    public FrameSnapshot(Matrix4fc projection, Matrix4fc viewRotation,
                         double cameraX, double cameraY, double cameraZ,
                         boolean cameraInMedium, LinearRgb cameraMedium,
                         double timeSeconds, double metersPerWorldUnit, long sceneId,
                         List<DamageOverlay> damageOverlays) {
        this.projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
        this.viewRotation = new Matrix4f(Objects.requireNonNull(viewRotation, "viewRotation"));
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.cameraInMedium = cameraInMedium;
        this.cameraMedium = Objects.requireNonNull(cameraMedium, "cameraMedium");
        this.timeSeconds = timeSeconds;
        this.metersPerWorldUnit = metersPerWorldUnit;
        this.sceneId = sceneId;
        this.damageOverlays = List.copyOf(damageOverlays);
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

    public boolean cameraInMedium() {
        return cameraInMedium;
    }

    public LinearRgb cameraMedium() {
        return cameraMedium;
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

    public List<DamageOverlay> damageOverlays() {
        return damageOverlays;
    }
}
