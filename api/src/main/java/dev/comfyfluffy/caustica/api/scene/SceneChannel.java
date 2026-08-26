package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.ResourceId;

/**
 * Scene identity and lifetime, reached from {@link CausticaApi#scenes()}.
 *
 * <p>A scene is a coordinate system, an acceleration structure, and a set of lights. Placements
 * ({@code GeometryChannel}) and lights ({@code LightChannel}) are made <em>into</em> one; meshes and
 * materials are not, and that split is the whole hierarchy:
 *
 * <ul>
 * <li><b>A mesh is scene-independent</b> because it is an acceleration-structure input, and a build does
 *     not know which top-level structure will reference it. The same mesh placed in two scenes costs one
 *     build.</li>
 * <li><b>A material is scene-independent</b> because a hit record is selected by
 *     {@code instanceShaderBindingTableRecordOffset} plus {@code geometryIndex} against one shader binding
 *     table. Every scene a single trace can reach therefore shares one material table, and an id means the
 *     same thing everywhere.</li>
 * <li><b>A placement and a light are scene-scoped</b> because both are expressed in the scene's
 *     coordinate system and both are gathered by structures the scene owns.</li>
 * <li><b>The environment is scene-owned</b> because it is what a ray that leaves the geometry finds, and
 *     which geometry it left is the scene's. It is not a slot: implementations are registered as a set and
 *     a scene names one, exactly as a material names a surface, so two scenes in one program can have
 *     different skies without either extension knowing the other exists.</li>
 * </ul>
 *
 * <p><b>The renderer traces the camera's scene and no other.</b> Which scene that is comes from the host
 * adapter, not from extensions — choosing what the renderer traces is not a way to add to the image — and
 * it is reported to sources as {@link SceneFrameContext#scene()}. Other scenes stay retained and are not
 * traced, so content prepared in one is held at full memory cost and contributes nothing until the camera
 * is there.
 */
public interface SceneChannel {
    /**
     * Resolve a registered environment implementation to the handle a scene names.
     *
     * <p>Bound to the runtime activation for the reason {@code MaterialChannel.surface} is: the compiled
     * closure changes with feature selection, so an id resolved here dies with the program that produced
     * it. Check {@link #environmentAvailable} first to fall back instead of catching.
     *
     * @throws IllegalStateException if {@code environment} names no implementation compiled into the
     *         active world program
     */
    EnvironmentId environment(ResourceId environment);

    /**
     * Whether an environment implementation compiled into the active world program. The non-throwing
     * companion to {@link #environment}.
     */
    boolean environmentAvailable(ResourceId environment);

    /**
     * Create a scene and return an id usable immediately.
     *
     * <p>Synchronous for the reason material registration is: a scene is a table entry and an empty
     * top-level structure, not acceleration work, so there is nothing to defer — and immediacy is what
     * keeps placements and lights free of cross-channel ordering. Both name a scene, and neither could do
     * so if creation published on a batch boundary.
     *
     * <p>A scene has an environment from the moment it exists, because there is no sensible thing for a
     * ray leaving its geometry to find otherwise. {@code parameters} is an uninterpreted 64-bit word
     * reaching the implementation for this scene only — wide enough to be a device address, so a sky's
     * per-scene state is whatever buffer the source points it at.
     *
     * @throws IllegalStateException if no render session is active
     */
    SceneId newScene(EnvironmentId environment, long parameters);

    /**
     * Replace a scene's environment implementation and its word.
     *
     * <p>Takes effect for the next frame the scene is traced. Nothing is retired: the word is copied and
     * whatever it addresses belongs to the source, which releases the displaced buffer through
     * {@link CausticaApi#gpu()} once it is done with it.
     *
     * @throws IllegalArgumentException if this channel did not issue {@code scene}, or already dropped it
     * @throws IllegalStateException if no render session is active
     */
    void setEnvironment(SceneId scene, EnvironmentId environment, long parameters);

    /**
     * Remove a scene, every placement in it, and every light in it.
     *
     * <p>Cascading rather than rejected-while-occupied, because a scene is normally filled by several
     * sources and no one of them can know when the last has left. Their ids go stale, so a source that has
     * not caught up is rejected on its next submission rather than writing into somebody else's scene —
     * the same protection {@link #generation()} gives.
     *
     * <p>No retirement callback comes with this. A placement owns nothing to reclaim, and a light's
     * parameter buffer belongs to whoever published it: that source releases it through
     * {@link CausticaApi#gpu()} once it learns the scene is gone. Meshes and materials are untouched,
     * being scene-independent.
     *
     * @throws IllegalArgumentException if this channel did not issue {@code scene}, or already dropped it
     * @throws IllegalStateException if no render session is active
     */
    void dropScene(SceneId scene);

    /**
     * Changes whenever every scene was emptied out from under its sources — a new render session, a device
     * loss. Ids issued before a change no longer resolve, so operations naming them are rejected.
     *
     * <p>One generation covers scenes, placements and lights, because a scene is what holds the other two:
     * nothing survives its scene, and no scene survives a change here. This is why a source can submit
     * without registering anything — compare a long against what you last saw and rebuild if it moved.
     * Missing the change is not silent either, since the next submission is rejected rather than landing
     * nowhere.
     */
    long generation();
}
