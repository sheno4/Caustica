module indirect_ser;

import caustica_api;
import indirect_core;
import trace_reordered;

[shader("raygeneration")]
void main<TC : IComposition>() {
    runIndirect<TC, ReorderedRadianceTrace>();
}
