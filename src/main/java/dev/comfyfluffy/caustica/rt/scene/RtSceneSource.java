package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.geometry.RtGeometryAbi;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/** Optimized GPU-scene seam implemented by a registered scene provider. */
public interface RtSceneSource {
    /** The currently publishable retained scene, or {@code null} while the source is not ready. */
    Retained retainedScene();

    /** Append frame-varying geometry to the retained/provider table assembled by the renderer. */
    Frame beginFrame(GpuContext ctx, Retained retained, List<RtAccel.Instance> baseInstances,
                     RtGeometryAbi.TablePrefix geometryTable, Camera camera);

    int bindlessTextureCapacity();

    void resetBindlessTextures(int capacity);

    void uploadPendingTextures(RtPipeline pipeline, long sampler);

    /** Stable retained geometry and source-owned light-grid bindings for one renderer frame. */
    record Retained(SceneOrigin origin, List<RtAccel.Instance> instances,
                    RtGeometryAbi.TablePrefix geometryTable, LightGrid lightGrid) {
        public Retained {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(instances, "instances");
            Objects.requireNonNull(geometryTable, "geometryTable");
            Objects.requireNonNull(lightGrid, "lightGrid");
        }
    }

    /** Camera inputs needed by a frame-varying geometry source. Matrices remain renderer-owned. */
    record Camera(double x, double y, double z, Matrix4f projection, Matrix4f viewRotation) {
        public Camera {
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(viewRotation, "viewRotation");
        }
    }

    /** Generic retained light-grid bindings consumed by the world renderer. */
    record LightGrid(long lightAddress, long globalAliasAddress, long localAliasAddress,
                     long cellAddress, long spanAddress,
                     float rebaseOffsetX, float rebaseOffsetY, float rebaseOffsetZ,
                     float inverseGlobalPowerSum,
                     float originX, float originY, float originZ, float cellSize,
                     int dimensionX, int dimensionY, int dimensionZ, int lightCount) {
    }

    /** Frame-varying geometry plus the source-owned lifetime manifest for a submitted frame. */
    interface Frame {
        List<RtAccel.Instance> dynamicInstances();

        List<RtAccel.PreparedBlas> blasBuilds();

        long geometryTableAddress();

        void markGraphicsUse(GraphicsUse graphicsUse);
    }
}
