module indirect_ser;

import caustica_api;
import retained_indirect;
import retained_trace_reordered;

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runRetainedIndirect<TComposition, RetainedReorderedTrace>();
}
