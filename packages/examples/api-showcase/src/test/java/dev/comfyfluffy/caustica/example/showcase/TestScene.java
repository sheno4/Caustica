package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.*;
import dev.comfyfluffy.caustica.api.scene.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class TestScene implements SceneChannel, MeshPreparer {
    final List<List<SceneEdit>> batches = new ArrayList<>();
    final List<MeshBuild<?>> builds = new ArrayList<>();
    final List<Runnable> completions = new ArrayList<>();
    boolean delayed;
    boolean rejectNext;
    Runnable onEdit = () -> { };
    public InstanceId newInstance() { return new InstanceId() { }; }
    public LightId newLight() { return new LightId() { }; }
    public void edit(List<? extends SceneEdit> edits) {
        if (rejectNext) { rejectNext=false; throw new IllegalArgumentException("rejected"); }
        batches.add(List.copyOf(edits)); onEdit.run();
    }
    List<SceneEdit> last() { return batches.getLast(); }
    public <N> CompletableFuture<ReadyMesh<N>> prepare(ShaderDataType<N> type, MeshBuild<N> build, ReadyMesh<N> source) {
        builds.add(build);
        var refs = Collections.newSetFromMap(new IdentityHashMap<ResourceRef,Boolean>());
        refs.add(build.positions().resource()); refs.add(build.indices().resource());
        for (var geometry : build.geometries()) {
            if (geometry.surface()!=null) refs.add(geometry.surface().bindingData().resource());
            if (geometry.volume()!=null) refs.add(geometry.volume().bindingData().resource());
        }
        var owners = refs.stream().map(ResourceRef::retain).toList();
        var mesh = new Mesh<N>(type, TestResource.create(() -> owners.forEach(ResourceOwner::close)));
        var result = new CompletableFuture<ReadyMesh<N>>();
        if (delayed) completions.add(() -> result.complete(mesh)); else result.complete(mesh);
        return result;
    }
    void complete() { var ready=List.copyOf(completions);completions.clear();ready.forEach(Runnable::run); }
    private record Mesh<N>(ShaderDataType<N> instanceDataType, ResourceOwner owner) implements ReadyMesh<N> {
        public ReadyMesh<N> retain() { return new Mesh<>(instanceDataType, owner.retain()); }
        public ResourceRef reference() { return owner.reference(); }
        public void close() { owner.close(); }
    }
}
