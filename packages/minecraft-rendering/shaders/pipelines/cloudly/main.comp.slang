import caustica_cloudly_clouds;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) { runCloudlyClouds(id); }
