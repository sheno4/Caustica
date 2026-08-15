package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.SceneMesh;

/** Resolves stable public material names into the current GPU material epoch. */
@FunctionalInterface
public interface RtGeometryMaterialResolver {
    ResolvedMaterial resolve(SceneMesh.MaterialReference material, SceneMesh.Coverage coverage);

    record ResolvedMaterial(int bindingId, int sbtClass) {
        public ResolvedMaterial {
            if (bindingId < 0 || sbtClass < 0 || sbtClass > 2) {
                throw new IllegalArgumentException("invalid resolved geometry material");
            }
        }
    }
}
