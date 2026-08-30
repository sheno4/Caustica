package dev.comfyfluffy.caustica.engine.scene;

import java.util.List;

/** Ordered retained-geometry changes for one atomic publication. */
public record RetainedSceneGeometryDelta(long revision, List<Mutation> mutations) {
    public RetainedSceneGeometryDelta {
        mutations = List.copyOf(mutations);
    }

    public sealed interface Mutation permits SetMesh, DropMesh, SetInstance, DropInstance { }
    public record SetMesh(RetainedSceneSnapshot.Mesh mesh) implements Mutation { }
    public record DropMesh(long identity) implements Mutation { }
    public record SetInstance(RetainedSceneSnapshot.Instance instance) implements Mutation { }
    public record DropInstance(long identity) implements Mutation { }
}
