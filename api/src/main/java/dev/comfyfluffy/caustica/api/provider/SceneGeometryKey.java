package dev.comfyfluffy.caustica.api.provider;

/** Stable source-local retained-geometry identity. Domains partition independent producer namespaces. */
public record SceneGeometryKey(long domain, long value) {
    public static SceneGeometryKey of(long value) {
        return new SceneGeometryKey(0L, value);
    }
}
