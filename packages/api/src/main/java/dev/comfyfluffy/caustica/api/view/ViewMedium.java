package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;

import java.util.Objects;

/** The homogeneous medium containing the camera origin for one rendered view. */
public sealed interface ViewMedium permits ViewMedium.Vacuum, ViewMedium.Volume {
    /** No participating medium contains the camera origin. */
    enum Vacuum implements ViewMedium {
        INSTANCE
    }

    /**
     * A typed volume implementation and the binding words selected at the camera origin.
     * Construction borrows both data handles. The caller keeps them open until frame capture has retained
     * independent claims, and closes its own handles when they are no longer needed.
     */
    record Volume<B, N>(VolumeId<B, N> implementation, ShaderData<B> bindingData,
                        ShaderData<N> instanceData) implements ViewMedium {
        public Volume {
            Objects.requireNonNull(implementation, "implementation");
            Objects.requireNonNull(bindingData, "bindingData");
            Objects.requireNonNull(instanceData, "instanceData");
        }
    }
}
