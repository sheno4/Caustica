/* SPDX-License-Identifier: LGPL-3.0-or-later */
// Fixed-size GPU exclusive scan for the original catalogue's 4096 hash buckets.
// Single-workgroup Blelloch upsweep/downsweep: no device-wide spin waiting,
// architecture-specific subgroup size, or CPU readback.
[[vk::binding(0, 0)]] RWStructuredBuffer<uint> Counts;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> First;
struct Parameters { uint count; uint3 padding; };
struct Push { uint64_t parametersAddress; uint64_t bindingsAddress; };
[[vk::push_constant]] Push originalPush;
groupshared uint scratch[4096];

[numthreads(256, 1, 1)]
void main(uint3 thread : SV_GroupThreadID) {
    if (((Parameters*)originalPush.parametersAddress)->count != 4096) return;
    for (uint i = thread.x; i < 4096; i += 256) scratch[i] = Counts[i];
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = 1; stride < 4096; stride *= 2) {
        for (uint end = (thread.x + 1) * (2 * stride) - 1; end < 4096; end += 256 * (2 * stride))
            scratch[end] += scratch[end - stride];
        GroupMemoryBarrierWithGroupSync();
    }
    if (thread.x == 0) scratch[4095] = 0;
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = 2048; stride > 0; stride /= 2) {
        for (uint end = (thread.x + 1) * (2 * stride) - 1; end < 4096; end += 256 * (2 * stride)) {
            uint left = scratch[end - stride];
            scratch[end - stride] = scratch[end];
            scratch[end] += left;
        }
        GroupMemoryBarrierWithGroupSync();
    }
    for (uint i = thread.x; i < 4096; i += 256) {
        First[i] = scratch[i];
        Counts[i] = 0; // Original fill reuses the counts as per-bucket cursors.
    }
}
