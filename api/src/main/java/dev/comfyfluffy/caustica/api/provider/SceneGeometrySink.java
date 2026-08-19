package dev.comfyfluffy.caustica.api.provider;

/** Source-local atomic retained-geometry updates. Keys are stable only within one scene provider. */
public interface SceneGeometrySink {
    /** Submit one atomic group. Independent group keys may become visible independently. */
    default void submit(long groupKey, java.util.List<Operation> operations) {
        submit(SceneGeometryKey.of(groupKey), operations, () -> { });
    }

    default void submit(long groupKey, java.util.List<Operation> operations,
                        Runnable onPublished) {
        submit(SceneGeometryKey.of(groupKey), operations, onPublished);
    }

    /**
     * Submit one atomic group and observe when it becomes visible in the retained scene. The callback runs
     * on the renderer thread. It is discarded if the provider stops or fails before publication.
     */
    void submit(SceneGeometryKey groupKey, java.util.List<Operation> operations,
                Runnable onPublished);

    sealed interface Operation permits Put, Drop, Place, Transform, Remove { }

    /** Retain or replace mesh data. The renderer selects and schedules its acceleration update. */
    record Put(SceneGeometryKey residentKey, SceneMesh mesh) implements Operation {
        public Put(long residentKey, SceneMesh mesh) {
            this(SceneGeometryKey.of(residentKey), mesh);
        }
    }

    /** Remove a retained mesh. */
    record Drop(SceneGeometryKey residentKey) implements Operation {
        public Drop(long residentKey) { this(SceneGeometryKey.of(residentKey)); }
    }

    /** Add or replace one world-space placement of a retained mesh. */
    record Place(SceneGeometryKey instanceKey, SceneGeometryKey residentKey, GeometryTransform transform, int mask) implements Operation {
        public Place(long instanceKey, long residentKey, GeometryTransform transform, int mask) {
            this(SceneGeometryKey.of(instanceKey), SceneGeometryKey.of(residentKey), transform, mask);
        }
        public Place(long instanceKey, long residentKey, GeometryTransform transform) {
            this(instanceKey, residentKey, transform, 0xff);
        }
    }

    /** Update a published or earlier-accepted placement without changing its retained mesh target. */
    record Transform(SceneGeometryKey instanceKey, GeometryTransform transform, int mask) implements Operation { }

    /** Remove one placement. Omitted placements remain published. */
    record Remove(SceneGeometryKey instanceKey) implements Operation {
        public Remove(long instanceKey) { this(SceneGeometryKey.of(instanceKey)); }
    }
}
