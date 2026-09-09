package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.geometry.ReadyMesh;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Owner-local scene identities and producer mesh claims. */
public final class SceneContributionChannel implements SceneChannel, MeshPreparer {
    final SceneDirectory directory;
    boolean acceptingIdentities = true;
    boolean acceptingEdits = true;
    final Set<ReadyMesh<?>> meshes = new LinkedHashSet<>();
    final Set<CompletableFuture<?>> preparations = new LinkedHashSet<>();

    SceneContributionChannel(SceneDirectory directory) {
        this.directory = directory;
    }

    @Override
    public InstanceId newInstance() {
        return directory.newInstance(this);
    }

    @Override
    public LightId newLight() {
        return directory.newLight(this);
    }

    @Override
    public void edit(List<? extends SceneEdit> edits) {
        directory.edit(this, edits);
    }

    @Override
    public <N> CompletableFuture<ReadyMesh<N>> prepare(
            ShaderDataType<N> type, MeshBuild<N> build, ReadyMesh<N> source) {
        return directory.prepare(this, type, build, source);
    }

    public void quiesce() {
        directory.quiesce(this);
    }

    public void invalidate() {
        directory.invalidate(this);
    }

    public void drain() {
        directory.drain(this);
    }
}
