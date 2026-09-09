package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Immutable current-instance pages fix TLAS fields; retained source revisions own their BLAS addresses. */
final class RtPackedTlasPages {
    private IdentityHashMap<List<?>, Page> cached = new IdentityHashMap<>();
    private ByteBuffer scratch = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

    /** The serial scene worker owns this cache and scratch; previous motion does not affect TLAS fields. */
    <T> List<ByteBuffer> resolve(List<T> instances, SceneOrigin origin, TlasBuilder.InstanceWriter<T> writer) {
        var result = new ArrayList<ByteBuffer>();
        var next = new IdentityHashMap<List<?>, Page>();
        for (List<T> input : SnapshotList.pagesOf(instances)) {
            if (input.isEmpty()) continue;
            Page page = cached.get(input);
            if (page == null || !page.origin.equals(origin)) {
                page = new Page(origin, pack(input, writer));
            }
            result.add(page.bytes);
            next.put(input, page);
        }
        cached = next;
        return List.copyOf(result);
    }

    private <T> ByteBuffer pack(List<T> input, TlasBuilder.InstanceWriter<T> writer) {
        int bytes = Math.multiplyExact(input.size(), VkAccelerationStructureInstanceKHR.SIZEOF);
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        var records = VkAccelerationStructureInstanceKHR.create(MemoryUtil.memAddress(scratch), input.size());
        int index = 0;
        for (T instance : input) writer.write(instance, records.get(index++));
        // Published pages own their bytes independently of the reusable native struct storage.
        ByteBuffer packed = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        packed.put(0, scratch, 0, bytes);
        return packed.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private record Page(SceneOrigin origin, ByteBuffer bytes) {}
}
