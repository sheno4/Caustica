/**
 * Session-retained meshes and scene-targeted placements.
 *
 * <p>Meshes belong to their issuing contribution rather than to a scene; instances place them into
 * same-session scenes. Mesh and instance ids remain owner-local mutation capabilities. Scene, surface, and
 * volume ids are non-owning selection references which may be handed across contribution boundaries without
 * transferring removal authority. A mesh's generic parameter is the shader-data schema shared by all of
 * its placements and shading slots.
 */
package dev.comfyfluffy.caustica.api.geometry;
