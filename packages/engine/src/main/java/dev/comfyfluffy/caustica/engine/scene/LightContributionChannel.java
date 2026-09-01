package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

/** Owner-scoped light mutation capability for one retained scene directory. */
public final class LightContributionChannel implements LightChannel {
    final SceneDirectory directory;
    final ContributionOwner owner;
    boolean acceptingIdentities = true;
    boolean acceptingSubmissions = true;

    LightContributionChannel(SceneDirectory directory, ContributionOwner owner) {
        this.directory = directory;
        this.owner = owner;
    }

    @Override public LightId newLight() { return directory.newLight(this); }
    @Override public void submit(RetainedBatch<Operation> batch) { directory.submitLights(this, batch); }
    public void quiesce() { directory.quiesce(this); }
    public void invalidate() { directory.invalidate(this); }
    public void drain() { directory.drain(this); }
}
