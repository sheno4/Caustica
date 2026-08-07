package dev.comfyfluffy.caustica.api.provider;

import net.minecraft.resources.Identifier;

/**
 * Where a {@link LightProvider} hands off the lights it wants in the scene, once per frame, via
 * {@link LightProvider#submitLights(LightSink)}.
 *
 * <p>Placeholder surface: nothing downstream consumes a submission yet. The light system has no dynamic
 * tier a provider-supplied light could join — every record in today's light database still carries a
 * Minecraft chunk-section coordinate a provider light has no equivalent for (see
 * {@code LIGHT_SYSTEM_PLAN.md} L2). Calling this today records intent for design/API feedback, not
 * shadows; it is wired up so an extension can be written and reviewed against the real shape before the
 * light system side exists to honour it.
 */
public interface LightSink {
    /**
     * A distant directional light (sun/moon-like): no position, just a unit direction and absolute
     * illuminance in lux, scene-linear BT.709.
     */
    void directionalLight(Identifier id, float dirX, float dirY, float dirZ, float illuminanceLux);
}
