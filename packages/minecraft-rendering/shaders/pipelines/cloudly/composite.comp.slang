import caustica_cloudly_composite;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) { runCloudlyComposite(id); }
