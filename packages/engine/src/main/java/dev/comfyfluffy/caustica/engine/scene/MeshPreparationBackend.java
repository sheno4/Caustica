package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;

import java.util.concurrent.CompletableFuture;

/** Builds an immutable native mesh; source is borrowed through preparation completion. */
public interface MeshPreparationBackend {
    CompletableFuture<ResourceOwner> prepare(RetainedSceneSnapshot.Mesh mesh, ResourceOwner source);
}
