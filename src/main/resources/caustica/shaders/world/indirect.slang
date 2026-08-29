module indirect;

import caustica_api;
import retained_indirect;
import retained_trace_ordinary;

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runRetainedIndirect<TComposition, RetainedOrdinaryTrace>();
}
