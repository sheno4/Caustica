package dev.comfyfluffy.caustica.minecraft.api;

/** Fixed-resolution premultiplied linear-BT.709 emission color and coverage. */
public interface MinecraftEmissionFootprint {
    int resolution();
    int sampleIndex(float coordinate);
    float r(int x, int y);
    float g(int x, int y);
    float b(int x, int y);
    float weight(int x, int y);
}
