import caustica_minecraft_sky_lut_ambient;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) { runSkyAmbient(); }
