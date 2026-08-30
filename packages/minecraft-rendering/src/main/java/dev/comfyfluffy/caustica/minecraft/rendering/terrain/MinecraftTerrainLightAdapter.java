package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;

import java.util.ArrayList;

/** Converts Minecraft terrain-emitter sidecars into engine light descriptors. */
public final class MinecraftTerrainLightAdapter {
    public static final int FLOATS_PER_LIGHT = 20;
    public static final double METERS_PER_WORLD_UNIT = 1.0;

    private MinecraftTerrainLightAdapter() {
    }

    public static MinecraftTerrainLightBatch describe(long sectionKey, long revision,
                                                      double originX, double originY, double originZ,
                                                      float[] records) {
        int lightCount = records.length / FLOATS_PER_LIGHT;
        ArrayList<LightDescriptor.Finite> descriptors = new ArrayList<>(lightCount);
        for (int source = 0; source < records.length;
             source += FLOATS_PER_LIGHT) {
            double ux = records[source + 8];
            double uy = records[source + 9];
            double uz = records[source + 10];
            double vx = records[source + 12];
            double vy = records[source + 13];
            double vz = records[source + 14];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            double facing = nx * records[source + 4]
                    + ny * records[source + 5]
                    + nz * records[source + 6];
            if (facing < 0.0) {
                vx = -vx;
                vy = -vy;
                vz = -vz;
            }
            descriptors.add(new LightDescriptor.Rectangle(
                    records[source] + originX,
                    records[source + 1] + originY,
                    records[source + 2] + originZ,
                    ux, uy, uz, vx, vy, vz,
                    records[source + 16], records[source + 17], records[source + 18]));
        }
        return new MinecraftTerrainLightBatch(sectionKey, revision, descriptors);
    }
}
