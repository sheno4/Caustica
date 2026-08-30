module caustica_minecraft_sky_lut_types;

import caustica_resources;
import caustica_minecraft_sky_atmosphere;

/** Complete descriptor-heap root for one sky LUT compute dispatch. */
public struct SkyLutPush {
    public uint destinationImageIndex;
    public SampledTexture2DIndex transmittanceImage;
    public SampledTexture2DIndex multiScatterImage;
    public SamplerIndex sampler;
    public SkyInputs inputs;
    /** Stable LUT-lifetime destination updated by the sky-view dispatch before world tracing. */
    public uint64_t skyInputsAddress;
};
