package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;

/** One owning claim on an immutable, GPU-ready mesh revision, shareable across contributions and scenes. */
public interface ReadyMesh<N> extends ResourceOwner {
    ShaderDataType<N> instanceDataType();

    @Override ReadyMesh<N> retain();
}
