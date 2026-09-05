package dev.comfyfluffy.caustica.minecraft.rendering;
import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.*;
import dev.comfyfluffy.caustica.api.scene.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Controllable preparation completion and independently retained scene mesh claims. */
public final class PreparedScene implements MeshPreparer, SceneChannel {
    public final List<Job<?>> jobs = new ArrayList<>();
    public final List<List<? extends SceneEdit>> edits = new ArrayList<>();
    private final Map<InstanceId, ReadyMesh<?>> instances = new IdentityHashMap<>();
    public boolean reject;
    @Override public <N> CompletableFuture<ReadyMesh<N>> prepare(ShaderDataType<N> type, MeshBuild<N> build,
                                                                ReadyMesh<N> source) {
        var job = new Job<N>(type, source);
        jobs.add(job);
        return job.future;
    }
    @Override public InstanceId newInstance() { return new InstanceId() { }; }
    @Override public LightId newLight() { return new LightId() { }; }
    @Override public void edit(List<? extends SceneEdit> batch) {
        if (reject) { reject = false; throw new IllegalStateException("rejected edit"); }
        for (var edit : batch) {
            if (edit instanceof SceneEdit.SetInstance<?> set) {
                var old = instances.put(set.instance(), set.mesh().retain());
                if (old != null) old.close();
            } else if (edit instanceof SceneEdit.DropInstance drop) {
                var old = instances.remove(drop.instance());
                if (old != null) old.close();
            }
        }
        edits.add(List.copyOf(batch));
    }
    public static final class Job<N> {
        private final ShaderDataType<N> type;
        public final ReadyMesh<N> refitSource;
        public final CompletableFuture<ReadyMesh<N>> future = new CompletableFuture<>();
        public int releases;
        Job(ShaderDataType<N> type, ReadyMesh<N> source) { this.type = type; this.refitSource = source; }
        public void complete() { future.complete(new Mesh<>(type, TestResource.create(() -> releases++))); }
    }
    private record Mesh<N>(ShaderDataType<N> instanceDataType, ResourceOwner owner) implements ReadyMesh<N> {
        @Override public ReadyMesh<N> retain() { return new Mesh<>(instanceDataType, owner.retain()); }
        @Override public ResourceRef reference() { return owner.reference(); }
        @Override public void close() { owner.close(); }
    }
}
