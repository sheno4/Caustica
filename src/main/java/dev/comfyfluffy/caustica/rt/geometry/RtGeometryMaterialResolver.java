package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.provider.MaterialHandle;

/** Resolves stable public material names into the current GPU material epoch. */
@FunctionalInterface
public interface RtGeometryMaterialResolver {
    ResolvedMaterial resolve(MaterialHandle handle);

    record ResolvedMaterial(int bindingId, int sbtClass) {
        public ResolvedMaterial {
            if (bindingId < 0 || sbtClass < 0 || sbtClass > 2) {
                throw new IllegalArgumentException("invalid resolved geometry material");
            }
        }
    }
}
