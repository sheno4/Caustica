package dev.comfyfluffy.caustica.api.geometry;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import java.util.concurrent.CompletableFuture;
/**
 * Prepares geometry independently of scene membership. Completion returns a caller-owned ready claim.
 * Independent input claims are acquired before prepare returns and retained through preparation and by the resulting revision. Cancelling the returned future
 * abandons its result; accepted GPU work still completes and its resources are released afterwards.
 */
public interface MeshPreparer {
    default <N> CompletableFuture<ReadyMesh<N>> prepare(ShaderDataType<N> instanceDataType, MeshBuild<N> build) {
        return prepare(instanceDataType, build, null);
    }
    /**
     * Optionally reuses a compatible source for an out-of-place refit. The source remains unchanged and
     * is retained through completion. A null or incompatible source produces a new build.
     */
    <N> CompletableFuture<ReadyMesh<N>> prepare(ShaderDataType<N> instanceDataType, MeshBuild<N> build, ReadyMesh<N> refitSource);
}
