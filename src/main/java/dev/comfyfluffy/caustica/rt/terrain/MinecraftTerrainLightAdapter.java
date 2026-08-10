package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Converts Minecraft terrain-emitter sidecars into host-neutral engine light descriptors. */
final class MinecraftTerrainLightAdapter {
    static final double METERS_PER_WORLD_UNIT = 1.0;

    private MinecraftTerrainLightAdapter() {
    }

    static List<LightDescriptor> describe(List<RtLightHierarchy.SectionInput> sections,
                                          int rebaseX, int rebaseY, int rebaseZ,
                                          BooleanSupplier cancelled) {
        int lightCount = 0;
        for (RtLightHierarchy.SectionInput section : sections) {
            lightCount = Math.addExact(lightCount,
                    section.lights().length / RtLightCollector.FLOATS_PER_LIGHT);
        }
        ArrayList<LightDescriptor> descriptors = new ArrayList<>(lightCount);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            if ((sectionIndex & 63) == 0 && cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException(
                        "Superseded terrain light adaptation");
            }
            RtLightHierarchy.SectionInput section = sections.get(sectionIndex);
            double originX = section.sectionX() * 16.0 - rebaseX;
            double originY = section.sectionY() * 16.0 - rebaseY;
            double originZ = section.sectionZ() * 16.0 - rebaseZ;
            float[] records = section.lights();
            for (int source = 0; source < records.length;
                 source += RtLightCollector.FLOATS_PER_LIGHT) {
                long localIndex = source / RtLightCollector.FLOATS_PER_LIGHT;
                long key = ((long) section.sectionSlot() << 32) | localIndex;
                descriptors.add(new LightDescriptor.Rectangle(key,
                        records[source] + originX,
                        records[source + 1] + originY,
                        records[source + 2] + originZ,
                        records[source + 8], records[source + 9], records[source + 10],
                        records[source + 12], records[source + 13], records[source + 14],
                        records[source + 4], records[source + 5], records[source + 6],
                        records[source + 16], records[source + 17], records[source + 18]));
            }
        }
        return descriptors;
    }
}
