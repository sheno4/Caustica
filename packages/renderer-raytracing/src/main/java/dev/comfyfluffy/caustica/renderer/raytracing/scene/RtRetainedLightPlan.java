package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedLightRecordData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedLightRecordData.Float4;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend.SceneLight;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.List;

/** Packs public physical-light descriptors into the reflected scene-relative GPU ABI. */
final class RtRetainedLightPlan {
    static final int PARALLELOGRAM = 0;
    static final int SPOT = 1;
    static final int DISTANT = 2;
    static final int RECORD_BYTES = RetainedLightRecordData.BYTE_SIZE;

    private RtRetainedLightPlan() { }

    static ByteBuffer pack(List<SceneLight> lights, SceneOrigin origin, BitSet linkedEmitters) {
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(lights.size(), RECORD_BYTES))
                .order(ByteOrder.nativeOrder());
        for (int index = 0; index < lights.size(); index++) {
            ByteBuffer record = packed.slice(index * RECORD_BYTES, RECORD_BYTES)
                    .order(ByteOrder.nativeOrder());
            data(lights.get(index).descriptor(), origin, linkedEmitters.get(index)).write(record);
        }
        return packed.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private static RetainedLightRecordData data(LightDescriptor descriptor, SceneOrigin origin,
                                                boolean linkedEmitter) {
        int flags = linkedEmitter ? 1 : 0;
        return switch (descriptor) {
            case LightDescriptor.Parallelogram light -> new RetainedLightRecordData(
                    PARALLELOGRAM, flags, 0.0f, 0.0f,
                    position(light.positionX(), light.positionY(), light.positionZ(), origin),
                    vector(light.halfUx(), light.halfUy(), light.halfUz(), 0.0),
                    vector(light.halfVx(), light.halfVy(), light.halfVz(), 0.0),
                    vector(light.radianceRedCdM2(), light.radianceGreenCdM2(),
                            light.radianceBlueCdM2(), 0.0));
            case LightDescriptor.Spot light -> new RetainedLightRecordData(
                    SPOT, flags, finiteFloat(light.rangeMeters()), 0.0f,
                    position(light.positionX(), light.positionY(), light.positionZ(), origin),
                    vector(light.directionX(), light.directionY(), light.directionZ(),
                            light.halfAngleRadians()),
                    zero(),
                    vector(light.intensityRedCandela(), light.intensityGreenCandela(),
                            light.intensityBlueCandela(), 0.0));
            case LightDescriptor.Distant light -> new RetainedLightRecordData(
                    DISTANT, flags, 0.0f, finiteFloat(light.angularRadiusRadians()),
                    vector(light.directionX(), light.directionY(), light.directionZ(), 0.0),
                    zero(), zero(), vector(light.illuminanceRedLux(), light.illuminanceGreenLux(),
                            light.illuminanceBlueLux(), 0.0));
        };
    }

    private static Float4 position(double x, double y, double z, SceneOrigin origin) {
        return vector(origin.relativeX(x), origin.relativeY(y), origin.relativeZ(z), 1.0);
    }

    private static Float4 vector(double x, double y, double z, double w) {
        return new Float4(finiteFloat(x), finiteFloat(y), finiteFloat(z), finiteFloat(w));
    }

    private static float finiteFloat(double value) {
        float packed = (float) value;
        if (!Float.isFinite(packed)) throw new IllegalArgumentException("light value exceeds GPU float range");
        return packed;
    }

    private static Float4 zero() { return new Float4(0.0f, 0.0f, 0.0f, 0.0f); }
}
