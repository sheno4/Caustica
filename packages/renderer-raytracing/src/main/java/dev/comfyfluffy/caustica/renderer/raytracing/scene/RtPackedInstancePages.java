package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Worker-owned packed metadata pages; prepared revisions retain the source resources behind their addresses. */
final class RtPackedInstancePages {
    static final int RECORDS_PER_PAGE = 128;
    private RtInstanceTablePlan table;
    private SceneOrigin origin;
    private List<ByteBuffer> pages = List.of();

    List<ByteBuffer> resolve(RtInstanceTablePlan current, SceneOrigin currentOrigin) {
        if (table == current && currentOrigin.equals(origin)) return pages;
        boolean sameOrigin = currentOrigin.equals(origin);
        var result = new ArrayList<ByteBuffer>();
        for (int first = 0; first < current.capacity(); first += RECORDS_PER_PAGE) {
            int count = Math.min(RECORDS_PER_PAGE, current.capacity() - first);
            if (sameOrigin && sameRecords(current, first, count)) {
                result.add(pages.get(first / RECORDS_PER_PAGE));
            } else {
                var bytes = ByteBuffer.allocate(Math.multiplyExact(count, RtInstanceTablePlan.RECORD_BYTES))
                        .order(ByteOrder.LITTLE_ENDIAN);
                current.write(bytes, currentOrigin, first, count);
                bytes.flip();
                result.add(bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
            }
        }
        table = current;
        origin = currentOrigin;
        pages = List.copyOf(result);
        return pages;
    }

    private boolean sameRecords(RtInstanceTablePlan current, int first, int count) {
        if (table == null || first >= table.capacity()
                || Math.min(RECORDS_PER_PAGE, table.capacity() - first) != count) return false;
        for (int slot = first; slot < first + count; slot++) {
            if (table.record(slot) != current.record(slot)) return false;
        }
        return true;
    }
}
