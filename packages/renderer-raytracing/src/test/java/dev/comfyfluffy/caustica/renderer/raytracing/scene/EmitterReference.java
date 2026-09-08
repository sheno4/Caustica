package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.PrimitiveEmitter;
import it.unimi.dsi.fastutil.longs.Long2IntFunction;

import java.nio.ByteBuffer;
import java.util.List;

/** Per-primitive lookup independent of the production run builder and revision cache. */
final class EmitterReference {
    static int index(int primitive, List<PrimitiveEmitter> ranges, Long2IntFunction indices) {
        for (var range : ranges) {
            if (primitive >= range.firstPrimitive()
                    && primitive < (long) range.firstPrimitive() + range.primitiveCount()) {
                return indices.getOrDefault(range.lightIdentity(), -1);
            }
        }
        return -1;
    }

    static void pack(ByteBuffer target, int first, int count, List<PrimitiveEmitter> ranges,
                     Long2IntFunction indices) {
        for (int primitive = first; primitive < first + count; primitive++) {
            target.putInt(index(primitive, ranges, indices));
        }
    }
}
