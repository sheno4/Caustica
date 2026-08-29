package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.RetainedLightCollection;

import java.util.ArrayList;

/** Converts Minecraft terrain-emitter sidecars into engine light descriptors. */
final class MinecraftTerrainLightAdapter {
    static final double METERS_PER_WORLD_UNIT = 1.0;

    private MinecraftTerrainLightAdapter() {
    }

    static RetainedLightCollection.Group describe(long groupKey, long revision,
                                                  double originX, double originY, double originZ,
                                                  float[] records) {
        int lightCount = records.length / RtLightCollector.FLOATS_PER_LIGHT;
        ArrayList<LightDescriptor.Finite> descriptors = new ArrayList<>(lightCount);
        for (int source = 0; source < records.length;
             source += RtLightCollector.FLOATS_PER_LIGHT) {
            long localIndex = source / RtLightCollector.FLOATS_PER_LIGHT;
            long key = localIndex;
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
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            descriptors.add(new LightDescriptor.Rectangle(key,
                    records[source] + originX,
                    records[source + 1] + originY,
                    records[source + 2] + originZ,
                    ux, uy, uz, vx, vy, vz, nx, ny, nz,
                    records[source + 16], records[source + 17], records[source + 18]));
        }
        return new RetainedLightCollection.Group(groupKey, revision, descriptors);
    }
}
