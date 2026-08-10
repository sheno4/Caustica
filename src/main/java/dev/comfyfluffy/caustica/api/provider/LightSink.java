package dev.comfyfluffy.caustica.api.provider;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

/**
 * Where a {@link LightProvider} hands off the lights it wants in the scene, once per frame, via
 * {@link LightProvider#submitLights(LightSink)}.
 *
 * <p>Descriptors are copied into the renderer's current-frame light snapshot. A provider must submit
 * every light it wants to keep each frame; omitting a light removes it immediately.
 */
@FunctionalInterface
public interface LightSink {
    /** Submit one host-neutral light descriptor in scene coordinates and physical units. */
    void submit(LightDescriptor light);
}
