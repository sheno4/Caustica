package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.engine.light.LightBvh;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Worker-side immutable retained-light hierarchy builder. Lights are Morton ordered by spatial cell;
 * batch-local aliases share those ranges. Proposal PDFs are reconstructed from the selected light's power, so the
 * GPU records contain only selection data.
 */
public final class RtRetainedLightSceneBuilder {
    /**
     * 8 floats / 32 B per GPU record: {@code {pos.xyz, packedLe} {halfU.xy, halfU.z|halfV.x, halfV.yz,
     * cell}}, half axes packed two per lane. 32 divides the 64 B cache line, so a record never
     * straddles one — at the previous 48 B roughly half of them did, costing two transactions each. RIS
     * fetches these at random indices, so that halving of transactions is the point of the layout.
     * <p>The rectangle area is NOT stored: it is exactly {@code 4*|halfU x halfV|}, since the collector
     * follows directly from the descriptor axes. The shader derives it from the cross product it
     * already computes for the emitter normal.
     */
    static final int GPU_FLOATS_PER_LIGHT = 8;
    private static final int MAX_PACKED_GRID_DIM = 1024;
    private static final int NORMAL_FLIP_BIT = 1 << 30;

    private RtRetainedLightSceneBuilder() {
    }

    /** Two halves into one float lane, low half = x — mirrors world_common.slang's unpackHalf2. */
    private static float packHalf2(float x, float y) {
        int bits = (Float.floatToFloat16(y) << 16) | (Float.floatToFloat16(x) & 0xFFFF);
        return Float.intBitsToFloat(bits);
    }

    public static Data build(List<RetainedLightBatch> batches, int rebaseX, int rebaseY, int rebaseZ,
                             double metersPerWorldUnit, BooleanSupplier cancelled) {
        List<RetainedLightBatch> orderedBatches = orderedBatches(batches, cancelled);
        int batchCapacity = 0;
        int totalLights = 0;
        int maxBatchLights = 0;
        for (int i = 0; i < orderedBatches.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            RetainedLightBatch batch = orderedBatches.get(i);
            int count = batch.lights().size();
            batchCapacity = Math.max(batchCapacity, batch.slot() + 1);
            totalLights = Math.addExact(totalLights, count);
            maxBatchLights = Math.max(maxBatchLights, count);
        }

        int[] batchFirstLights = new int[batchCapacity];
        int[] batchLightCounts = new int[batchCapacity];
        float[] packedLights = new float[Math.multiplyExact(totalLights, GPU_FLOATS_PER_LIGHT)];
        int[] lightCellCoords = new int[Math.multiplyExact(totalLights, 3)];
        double[] powers = new double[totalLights];
        ArrayList<RtRetainedLightGrid.BatchLights> gridBatches = new ArrayList<>(orderedBatches.size());

        int lightIndex = 0;
        double globalPower = 0.0;
        for (int batchIndex = 0; batchIndex < orderedBatches.size(); batchIndex++) {
            if ((batchIndex & 63) == 0) checkCancelled(cancelled);
            RetainedLightBatch batch = orderedBatches.get(batchIndex);
            int count = batch.lights().size();
            int first = lightIndex;
            double batchPower = 0.0;
            for (LightDescriptor.Finite descriptor : batch.lights()) {
                if (!(descriptor instanceof LightDescriptor.Rectangle light)) {
                    throw new IllegalArgumentException(
                            "The retained legacy GPU ABI accepts rectangle lights only");
                }
                int destination = lightIndex * GPU_FLOATS_PER_LIGHT;
                int cellDestination = lightIndex * 3;
                lightCellCoords[cellDestination] = batch.cellX();
                lightCellCoords[cellDestination + 1] = batch.cellY();
                lightCellCoords[cellDestination + 2] = batch.cellZ();
                float leR = (float) light.radianceRedCdM2();
                float leG = (float) light.radianceGreenCdM2();
                float leB = (float) light.radianceBlueCdM2();
                int packedLe = packR11G11B10(leR, leG, leB);
                float halfUx = (float) light.halfUx();
                float halfUy = (float) light.halfUy();
                float halfUz = (float) light.halfUz();
                float halfVx = (float) light.halfVx();
                float halfVy = (float) light.halfVy();
                float halfVz = (float) light.halfVz();
                packedLights[destination] = (float) (light.positionX() - rebaseX);
                packedLights[destination + 1] = (float) (light.positionY() - rebaseY);
                packedLights[destination + 2] = (float) (light.positionZ() - rebaseZ);
                packedLights[destination + 3] = Float.intBitsToFloat(packedLe);
                // Half axes at half precision: these are block-scale offsets from the rectangle centre,
                // well inside half's range and resolution. The centre itself stays f32 because the RIS
                // target divides by squared distance to it.
                packedLights[destination + 4] = packHalf2(halfUx, halfUy);
                packedLights[destination + 5] = packHalf2(halfUz, halfVx);
                packedLights[destination + 6] = packHalf2(halfVy, halfVz);
                float crossX = halfUy * halfVz - halfUz * halfVy;
                float crossY = halfUz * halfVx - halfUx * halfVz;
                float crossZ = halfUx * halfVy - halfUy * halfVx;
                if (crossX * light.normalX() + crossY * light.normalY()
                        + crossZ * light.normalZ() < 0.0) {
                    packedLights[destination + 7] = Float.intBitsToFloat(NORMAL_FLIP_BIT);
                }
                // Collector output and the packed GPU record are linear ACEScg/AP1.
                double luminance = 0.27222872 * unpackUnsignedFloat(packedLe & 0x7ff, 6)
                        + 0.67408177 * unpackUnsignedFloat((packedLe >>> 11) & 0x7ff, 6)
                        + 0.05368952 * unpackUnsignedFloat((packedLe >>> 22) & 0x3ff, 5);
                double area = 4.0 * Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
                double power = Math.max(0.0, area * luminance);
                powers[lightIndex] = power;
                batchPower += power;
                globalPower += power;
                lightIndex++;
            }
            batchFirstLights[batch.slot()] = first;
            batchLightCounts[batch.slot()] = count;
            if (batchPower > 0.0) {
                gridBatches.add(new RtRetainedLightGrid.BatchLights(first, count,
                        batch.cellX(), batch.cellY(), batch.cellZ(), batchPower));
            }
        }

        AliasData globalAliases = buildAlias(powers, 0, totalLights, cancelled);
        int[] localAliasIndices = new int[totalLights];
        float[] localAliasAccept = new float[totalLights];
        AliasScratch localScratch = new AliasScratch(maxBatchLights);
        for (int slot = 0; slot < batchCapacity; slot++) {
            if ((slot & 255) == 0) checkCancelled(cancelled);
            int count = batchLightCounts[slot];
            if (count == 0) continue;
            int first = batchFirstLights[slot];
            if (count > 0xffff) {
                throw new IllegalStateException("Batch light count exceeds Span16 capacity: " + count);
            }
            buildAliasInto(powers, first, count, localAliasIndices, localAliasAccept,
                    first, localScratch, cancelled);
        }

        RtRetainedLightGrid.Data grid = totalLights > 0
                ? RtRetainedLightGrid.build(gridBatches, rebaseX, rebaseY, rebaseZ, cancelled) : null;
        if (grid != null && (grid.dimX() > MAX_PACKED_GRID_DIM || grid.dimY() > MAX_PACKED_GRID_DIM
                || grid.dimZ() > MAX_PACKED_GRID_DIM)) {
            grid = null;
        }
        if (grid != null) {
            int gridCellX = (grid.originX() + rebaseX) / 16;
            int gridCellY = (grid.originY() + rebaseY) / 16;
            int gridCellZ = (grid.originZ() + rebaseZ) / 16;
            for (int i = 0; i < totalLights; i++) {
                int source = i * 3;
                int x = lightCellCoords[source] - gridCellX;
                int y = lightCellCoords[source + 1] - gridCellY;
                int z = lightCellCoords[source + 2] - gridCellZ;
                if ((x | y | z) < 0 || x >= MAX_PACKED_GRID_DIM || y >= MAX_PACKED_GRID_DIM
                        || z >= MAX_PACKED_GRID_DIM) {
                    throw new IllegalStateException("Light cell is outside packed light grid");
                }
                int destination = i * GPU_FLOATS_PER_LIGHT + 7;
                int flags = Float.floatToRawIntBits(packedLights[destination]) & NORMAL_FLIP_BIT;
                packedLights[destination] = Float.intBitsToFloat(flags | x | (y << 10) | (z << 20));
            }
        }
        // The CPU BVH is derived from the same immutable retained-light snapshot as the active grid. It stays
        // host-side because shaders require a sampling record that preserves the proposal PDF.
        List<LightDescriptor.Finite> descriptors = orderedBatches.stream()
                .flatMap(batch -> batch.lights().stream()).toList();
        LightBvh.Data lightBvh = LightBvh.build(descriptors, metersPerWorldUnit, cancelled);
        return new Data(packedLights, globalAliases,
                batchFirstLights, batchLightCounts,
                new AliasData(localAliasIndices, localAliasAccept), grid, lightBvh, totalLights,
                globalPower > 0.0 ? (float) (1.0 / globalPower) : 0.0f,
                rebaseX, rebaseY, rebaseZ, metersPerWorldUnit);
    }

    private static List<RetainedLightBatch> orderedBatches(List<RetainedLightBatch> batches,
                                                             BooleanSupplier cancelled) {
        if (batches.size() < 2) return batches;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int i = 0; i < batches.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            RetainedLightBatch batch = batches.get(i);
            minX = Math.min(minX, batch.cellX());
            minY = Math.min(minY, batch.cellY());
            minZ = Math.min(minZ, batch.cellZ());
        }
        final int originX = minX, originY = minY, originZ = minZ;
        ArrayList<RetainedLightBatch> ordered = new ArrayList<>(batches);
        ordered.sort(Comparator.comparingLong((RetainedLightBatch batch) -> mortonKey(
                        batch.cellX() - originX, batch.cellY() - originY,
                        batch.cellZ() - originZ))
                .thenComparingInt(RetainedLightBatch::slot));
        return ordered;
    }

    private static long mortonKey(int x, int y, int z) {
        return spread3(x) | (spread3(y) << 1) | (spread3(z) << 2);
    }

    private static long spread3(int value) {
        long x = Integer.toUnsignedLong(value) & 0x1fffffL;
        x = (x | x << 32) & 0x1f00000000ffffL;
        x = (x | x << 16) & 0x1f0000ff0000ffL;
        x = (x | x << 8) & 0x100f00f00f00f00fL;
        x = (x | x << 4) & 0x10c30c30c30c30c3L;
        return (x | x << 2) & 0x1249249249249249L;
    }

    private static AliasData buildAlias(double[] powers, int offset, int count,
                                        BooleanSupplier cancelled) {
        int[] alias = new int[count];
        float[] accept = new float[count];
        buildAliasInto(powers, offset, count, alias, accept,
                0, new AliasScratch(count), cancelled);
        return new AliasData(alias, accept);
    }

    /**
     * Vose alias-method table over {@code weights[weightOffset, weightOffset+count)}. Writes
     * {@code accept[destinationOffset+i]} and a LOCAL (0-based) alias index into
     * {@code alias[destinationOffset+i]} for {@code i} in {@code [0, count)}. No-op (both arrays
     * untouched) if the window is empty or its total weight is not positive. Returns the total
     * weight (0.0 in both those cases) so callers that also need it (e.g. a per-cell inverse-weight
     * normalizer) don't have to recompute it. Shared by {@link RtRetainedLightGrid}'s per-cell batch
     * distributions, which follow the same offset convention.
     */
    static double buildAliasInto(double[] weights, int weightOffset, int count,
                                 int[] alias, float[] accept, int destinationOffset,
                                 AliasScratch scratch, BooleanSupplier cancelled) {
        if (count == 0) return 0.0;
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            if ((i & 1023) == 0) checkCancelled(cancelled);
            total += weights[weightOffset + i];
        }
        if (!(total > 0.0)) return 0.0;

        double[] scaled = scratch.scaled;
        int[] small = scratch.small;
        int[] large = scratch.large;
        int smallCount = 0;
        int largeCount = 0;
        for (int i = 0; i < count; i++) {
            double weight = weights[weightOffset + i];
            scaled[i] = weight * count / total;
            if (scaled[i] < 1.0) small[smallCount++] = i;
            else large[largeCount++] = i;
        }
        while (smallCount > 0 && largeCount > 0) {
            int s = small[--smallCount];
            int l = large[--largeCount];
            accept[destinationOffset + s] = (float) scaled[s];
            alias[destinationOffset + s] = l;
            scaled[l] = scaled[l] + scaled[s] - 1.0;
            if (scaled[l] < 1.0) small[smallCount++] = l;
            else large[largeCount++] = l;
        }
        while (largeCount > 0) {
            int i = large[--largeCount];
            accept[destinationOffset + i] = 1.0f;
            alias[destinationOffset + i] = i;
        }
        while (smallCount > 0) {
            int i = small[--smallCount];
            accept[destinationOffset + i] = 1.0f;
            alias[destinationOffset + i] = i;
        }
        return total;
    }

    static int packR11G11B10(float r, float g, float b) {
        return packUnsignedFloat(r, 6) | (packUnsignedFloat(g, 6) << 11)
                | (packUnsignedFloat(b, 5) << 22);
    }

    private static int packUnsignedFloat(float value, int mantissaBits) {
        if (!(value > 0.0f)) return 0;
        if (!Float.isFinite(value)) return (30 << mantissaBits) | ((1 << mantissaBits) - 1);
        int exponent = Math.getExponent(value);
        int encodedExponent = exponent + 15;
        int mantissaScale = 1 << mantissaBits;
        if (encodedExponent <= 0) {
            int mantissa = Math.round(Math.scalb(value, 14 + mantissaBits));
            return Math.min(mantissa, mantissaScale - 1);
        }
        if (encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        int mantissa = Math.round((Math.scalb(value, -exponent) - 1.0f) * mantissaScale);
        if (mantissa == mantissaScale) {
            mantissa = 0;
            if (++encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        }
        return (encodedExponent << mantissaBits) | mantissa;
    }

    static float unpackUnsignedFloat(int bits, int mantissaBits) {
        int mantissaMask = (1 << mantissaBits) - 1;
        int mantissa = bits & mantissaMask;
        int exponent = (bits >>> mantissaBits) & 31;
        if (exponent == 0) return Math.scalb((float) mantissa, 1 - 15 - mantissaBits);
        return Math.scalb(1.0f + (float) mantissa / (1 << mantissaBits), exponent - 15);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Superseded light hierarchy build");
        }
    }

    static final class AliasScratch {
        final double[] scaled;
        final int[] small;
        final int[] large;

        AliasScratch(int capacity) {
            scaled = new double[capacity];
            small = new int[capacity];
            large = new int[capacity];
        }
    }

    public record AliasData(int[] aliasIndices, float[] accept) {
        long bytes() {
            return Math.multiplyExact((long) aliasIndices.length, 8L);
        }
    }

    public record Data(float[] packedLights, AliasData globalAliases,
                int[] batchFirstLights, int[] batchLightCounts,
                AliasData localAliases, RtRetainedLightGrid.Data grid, LightBvh.Data lightBvh, int lightCount,
                float invGlobalPowerSum,
                int rebaseX, int rebaseY, int rebaseZ, double metersPerWorldUnit) {
        long lightBytes() {
            return Math.multiplyExact((long) packedLights.length, Float.BYTES);
        }

    }
}
