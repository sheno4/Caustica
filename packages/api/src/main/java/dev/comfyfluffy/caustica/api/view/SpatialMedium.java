package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import java.util.Objects;

/**
 * A spatial medium selected for this view independently of its camera-containing homogeneous volume.
 * Positions evaluated by the implementation are relative to origin XYZ in absolute scene coordinates.
 * Construction borrows both data handles. Keep them open until frame capture retains its own claims.
 */
public record SpatialMedium<B, N>(VolumeId<B, N> implementation, ShaderData<B> bindingData,
                                   ShaderData<N> instanceData, double originX, double originY, double originZ) {
    public SpatialMedium(VolumeId<B, N> implementation, ShaderData<B> bindingData, ShaderData<N> instanceData) {
        this(implementation, bindingData, instanceData, 0, 0, 0);
    }

    public SpatialMedium {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(bindingData, "bindingData");
        Objects.requireNonNull(instanceData, "instanceData");
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("spatial medium origin must be finite");
        }
    }
}
