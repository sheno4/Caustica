/**
 * Immutable prepared meshes and scene-targeted placements.
 *
 * <p>A ready mesh is an owning claim on an immutable revision, shareable across contributions and scenes
 * in the same device session. Preparation acquires independent input claims; scene publication retains
 * the ready revision. Replacing or removing an instance does not invalidate other claims on its mesh.
 *
 * <p>Instance ids are mutation capabilities local to the issuing contribution. Scene, surface and volume
 * ids are non-owning selection references; sharing them does not transfer removal authority. A mesh's
 * generic parameter is the shader-data schema shared by all of its placements and shading slots.
 */
package dev.comfyfluffy.caustica.api.geometry;
