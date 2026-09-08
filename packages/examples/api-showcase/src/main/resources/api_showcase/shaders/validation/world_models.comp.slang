import showcase_surface;
import showcase_coverage;
import showcase_volume;
import showcase_environment;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    api_showcase.OpaqueSurface opaque;
    api_showcase.CutoutSurface cutout;
    api_showcase.StripedCoverage coverage;
    api_showcase.AbsorbingVolume volume;
    api_showcase.OverworldSky overworld;
    api_showcase.NetherSky nether;
    api_showcase.EndSky end;
}
