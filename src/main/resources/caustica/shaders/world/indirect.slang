module indirect;

import caustica_api;
import indirect_core;
import trace_ordinary;

[shader("raygeneration")]
void main<TC : IComposition>() {
    runIndirect<TC, OrdinaryRadianceTrace>();
}
