import nee_at_bake;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 groupId : SV_GroupID, uint groupThreadIndex : SV_GroupIndex,
          uint3 dispatchThreadId : SV_DispatchThreadID) {
    runNeeAtBake(groupId.x, groupThreadIndex, dispatchThreadId.x);
}
