module caustica_bloom_types;

public struct BloomPush {
    public int mode;
    public float threshold;
    public float softKnee;
    public float radius;
    // MODE_COMPOSITE only: how much of the finished pyramid to add onto the incoming chain image. The
    // pyramid itself is built unweighted, so strength stays one multiply in one place.
    public float compositeStrength;
};
