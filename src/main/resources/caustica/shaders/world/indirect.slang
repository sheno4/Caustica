module indirect;

import primary_rgen;
import caustica_api;

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runPrimary<TComposition>();
}
