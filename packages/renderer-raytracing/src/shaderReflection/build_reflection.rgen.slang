import build_stable_planes;
import fixed_composition;
[shader("raygeneration")]
void main() {
    runBuildStablePlanes<FixedComposition>();
}
