package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.List;

/** Owner-scoped geometry mutation capability for one retained scene directory. */
public final class GeometryContributionChannel implements GeometryChannel {
    final SceneDirectory directory;
    final ContributionOwner owner;
    boolean acceptingIdentities = true;
    boolean acceptingSubmissions = true;

    GeometryContributionChannel(SceneDirectory directory, ContributionOwner owner) {
        this.directory = directory;
        this.owner = owner;
    }

    @Override public <N> MeshId<N> newMesh(ShaderDataType<N> type) {
        return directory.newMesh(this, type);
    }
    @Override public InstanceId newInstance() { return directory.newInstance(this); }
    @Override public GeometryPublication submit(RetainedBatch<Operation> batch) {
        return directory.submitGeometry(this, batch);
    }
    @Override public GeometryPublication submitGroup(List<RetainedBatch<Operation>> batches) {
        return directory.submitGeometryGroup(this, batches);
    }
    @Override public GeometryPublication submitGroupWithLatest(
            List<RetainedBatch<Operation>> batches, List<LatestInstance> latestInstances) {
        return directory.submitGeometryGroupWithLatest(this, batches, latestInstances);
    }
    @Override public GeometryPublication submitWithLights(
            List<RetainedBatch<Operation>> geometryBatches, LightChannel lights,
            RetainedBatch<LightChannel.Operation> lightBatch) {
        return directory.submitGeometryAndLights(this, lights, geometryBatches, lightBatch);
    }
    public void quiesce() { directory.quiesce(this); }
    public void invalidate() { directory.invalidate(this); }
    public void drain() { directory.drain(this); }
}
