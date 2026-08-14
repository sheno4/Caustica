package dev.comfyfluffy.caustica.api.provider;

/**
 * Explicit retention for one provider's geometry. Keys are stable and local to that provider.
 *
 * <p>{@code retainMesh} declares a mesh's current data; call it only when the mesh is new or its
 * data actually changed, never on every frame. Comparing whether it changed is the caller's job:
 * only the source can tell cheaply, and the engine no longer diffs mesh bytes to find out.
 * {@code instance} declares one placement of an already-retained mesh and is called every frame the
 * instance should be visible. Omitting an instance this frame simply excludes it from this frame's
 * scene; the mesh itself stays resident until {@code releaseMesh} or the provider stops.
 */
public interface SceneGeometrySink {
    void retainMesh(long key, TriangleMesh mesh);

    void releaseMesh(long key);

    void instance(long key, long meshKey, GeometryTransform transform);
}
