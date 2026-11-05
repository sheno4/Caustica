package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

/** Native three-level cloud AO tiles retain their sampling centre until a complete volume is published. */
public final class CloudlyAmbientOcclusionState {
    private CloudlyAmbientOcclusionState() { }

    public record Center(float x, float y, float z) {
        public double[] values() { return new double[]{x, y, z}; }
    }

    public record Level(int step, Center lockedCenter, Center historyCenter) {
        public static Level initial() {
            var zero = new Center(0, 0, 0);
            return new Level(0, zero, zero);
        }
    }

    public record Update(Level afterSubmission, Center traceCenter, Center samplingCenter,
                         int tileX, int tileY, int groupsX, int groupsY, boolean publish) { }

    /** Source helper 0x145c6cfe0 snaps each cycle's origin to that level's native volume texel. */
    public static Update plan(Level previous, int level, float[] sourceOriginMeters) {
        float width = switch (level) {
            case 0 -> 30000f;
            case 1 -> 60000f;
            case 2 -> 130000f;
            default -> throw new IllegalArgumentException("Unknown native cloud AO level " + level);
        };
        Center center = previous.step() == 0
                ? new Center(snap(sourceOriginMeters[0], width / 512f),
                             snap(sourceOriginMeters[1], width / 512f),
                             snap(sourceOriginMeters[2], 15000f / 96f))
                : previous.lockedCenter();
        int rows = level == 0 ? 4 : 8;
        int tileX = (previous.step() / rows) * 512 / 8;
        int tileY = (previous.step() % rows) * 512 / rows;
        int nextStep = previous.step() + 1;
        boolean publish = nextStep == 8 * rows;
        Center sampling = publish ? center : previous.historyCenter();
        return new Update(new Level(publish ? 0 : nextStep, center, sampling), center, sampling,
                tileX, tileY, 8, 64 / rows, publish);
    }

    private static float snap(float value, float texel) {
        return (float)Math.floor(value / texel) * texel;
    }
}
