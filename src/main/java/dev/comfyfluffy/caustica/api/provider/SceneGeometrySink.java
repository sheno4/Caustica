package dev.comfyfluffy.caustica.api.provider;

/**
 * Desired retained geometry snapshot for one provider. Keys are stable and local to that provider;
 * omitting a mesh or instance releases it after its last rendered frame completes.
 */
public interface SceneGeometrySink {
    void retainMesh(long key, TriangleMesh mesh);

    void instance(long key, long meshKey, GeometryTransform transform);
}
