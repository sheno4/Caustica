package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend.SceneLight;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;

/** Packed light bytes depend on immutable descriptors, origin, and page-local emitter flags. */
final class RtPackedLightPages {
    private IdentityHashMap<List<SceneLight>, Page> cached = new IdentityHashMap<>();

    List<ByteBuffer> resolve(List<SceneLight> lights, SceneOrigin origin, BitSet linked,
                             RtFramePreparation preparation) {
        var result = new ArrayList<Page>();
        var missing = new ArrayList<Page>();
        var next = new IdentityHashMap<List<SceneLight>, Page>();
        int first = 0;
        for (var input : SnapshotList.pagesOf(lights)) {
            BitSet flags = linked.get(first, first + input.size());
            Page page = cached.get(input);
            if (page == null || !page.origin.equals(origin) || !page.flags.equals(flags)) {
                page = new Page(input, origin, flags);
                missing.add(page);
            }
            result.add(page);
            next.put(input, page);
            first += input.size();
        }
        if (!missing.isEmpty()) {
            var chunks = RtFramePreparation.chunks(missing, page -> page.input.size());
            preparation.run(chunks, chunk -> RtFramePreparation.measured("lights", 0,
                    chunk.stream().mapToInt(page -> page.input.size()).sum(),
                    () -> chunk.forEach(Page::pack)).run());
        }
        cached = next;
        return result.stream().map(page -> page.bytes).toList();
    }

    private static final class Page {
        final List<SceneLight> input;
        final SceneOrigin origin;
        final BitSet flags;
        ByteBuffer bytes;

        Page(List<SceneLight> input, SceneOrigin origin, BitSet flags) {
            this.input = input;
            this.origin = origin;
            this.flags = flags;
        }

        void pack() {
            bytes = RtRetainedLightPlan.pack(input, origin, flags);
        }
    }
}
