// Build-time specialization of the minimal ABI-6 world raygen path.
import primary_rgen;
import fixed_composition;

[shader("raygeneration")]
void main() {
    runPrimary<FixedComposition>();
}
