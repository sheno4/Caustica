import caustica_minecraft_sky_lut_view;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) { runSkyView(id); }
