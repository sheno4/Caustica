package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceChannel;
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.Objects;

/** Owner-scoped resource-generation authority. */
public final class ResourceContributionChannel implements ResourceChannel {
    final ResourceDirectory directory;
    final ContributionOwner owner;
    boolean accepting = true;

    ResourceContributionChannel(ResourceDirectory directory, ContributionOwner owner) {
        this.directory = directory;
        this.owner = owner;
    }

    @Override
    public ResourceGeneration create(Runnable retired) {
        return directory.create(this, Objects.requireNonNull(retired, "retired"));
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
