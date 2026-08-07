package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

/**
 * Where a {@link LightProvider} hands off the lights it wants in the scene, once per frame, via
 * {@link LightProvider#submitLights(LightSink)}.
 *
 * <p>Nothing downstream consumes a submission: the light system has no dynamic tier a
 * provider-supplied light could join, since every record in the light database carries a Minecraft
 * chunk-section coordinate a provider light has no equivalent for. Calling this records intent for
 * design and API feedback, not shadows; it is wired up so an extension can be written and reviewed
 * against the real shape.
 */
public interface LightSink {
    /**
     * A distant directional light (sun/moon-like): no position, just a unit direction and absolute
     * illuminance in lux, scene-linear BT.709.
     */
    void directionalLight(Identifier id, float dirX, float dirY, float dirZ, float illuminanceLux);
}
