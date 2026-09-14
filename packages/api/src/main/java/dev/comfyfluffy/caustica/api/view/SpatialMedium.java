package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import java.util.Objects;

/**
 * A spatial medium selected for this view independently of its camera-containing homogeneous volume.
 * Positions evaluated by the implementation are relative to origin XYZ in absolute scene coordinates.
 * A positive extinctionMajorant bounds every extinction channel everywhere, per scene unit; zero
 * selects numerical integration without a supplied bound.
 * Construction borrows both data handles. Keep them open until frame capture retains its own claims.
 */
public record SpatialMedium<B, N>(VolumeId<B, N> implementation, ShaderData<B> bindingData,
                                   ShaderData<N> instanceData, double originX, double originY, double originZ,
                                   Transport transport, float extinctionMajorant) {
    /** Selects ownership of camera-ray scattering; both modes participate along secondary rays. */
    public enum Transport { POST_PROCESS, PATH_TRACED }

    public SpatialMedium(VolumeId<B, N> implementation, ShaderData<B> bindingData,
                         ShaderData<N> instanceData, double originX, double originY, double originZ) {
        this(implementation, bindingData, instanceData, originX, originY, originZ, Transport.POST_PROCESS, 0);
    }
    public SpatialMedium(VolumeId<B, N> implementation, ShaderData<B> bindingData, ShaderData<N> instanceData) {
        this(implementation, bindingData, instanceData, 0, 0, 0);
    }

    public SpatialMedium {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(bindingData, "bindingData");
        Objects.requireNonNull(instanceData, "instanceData");
        Objects.requireNonNull(transport, "transport");
        if (!Float.isFinite(extinctionMajorant) || extinctionMajorant < 0) {
            throw new IllegalArgumentException("spatial medium extinction majorant must be finite and nonnegative");
        }
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("spatial medium origin must be finite");
        }
    }
}
