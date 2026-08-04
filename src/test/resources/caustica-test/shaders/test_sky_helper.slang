module test_sky_helper;

public float3 testSkyRadiance(float3 direction) {
    return float3(0.1, 0.2, 0.3) * (direction.y * 0.5 + 0.5);
}
