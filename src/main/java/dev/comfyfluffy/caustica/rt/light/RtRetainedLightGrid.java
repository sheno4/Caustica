package dev.comfyfluffy.caustica.rt.light;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * CPU builder for the cell-sized light candidate-proposal grid. Every nearby light remains
 * eligible, but a cell stores one weighted contiguous range per lit batch instead of duplicating
 * every light index. The span count is naturally bounded by the spatial neighborhood, not by an
 * arbitrary light cap.
 */
final class RtRetainedLightGrid {
    private static final int NEIGHBOR_RADIUS = 2;
    private static final int MAX_DENSE_GRID_CELLS = 4_000_000;

    private RtRetainedLightGrid() {
    }

    static Data build(List<BatchLights> batches, int rebaseX, int rebaseY, int rebaseZ,
                      BooleanSupplier cancelled) {
        if (batches.isEmpty()) return null;

        // Only powered batches enter this builder. Their +/-2-cell extents define the cells that
        // can use a local proposal; unlit source residency does not affect the proposal grid.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int poweredCount = 0;
        for (int i = 0; i < batches.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            BatchLights batch = batches.get(i);
            if (!(batch.power > 0.0)) continue;
            poweredCount++;
            if (batch.lightCount > 0xffff) {
                throw new IllegalStateException("Batch light count exceeds Span16 capacity: "
                        + batch.lightCount);
            }
            minX = Math.min(minX, batch.x - NEIGHBOR_RADIUS);
            minY = Math.min(minY, batch.y - NEIGHBOR_RADIUS);
            minZ = Math.min(minZ, batch.z - NEIGHBOR_RADIUS);
            maxX = Math.max(maxX, batch.x + NEIGHBOR_RADIUS);
            maxY = Math.max(maxY, batch.y + NEIGHBOR_RADIUS);
            maxZ = Math.max(maxZ, batch.z + NEIGHBOR_RADIUS);
        }
        if (poweredCount == 0) return null;
        int dimX = Math.addExact(Math.subtractExact(maxX, minX), 1);
        int dimY = Math.addExact(Math.subtractExact(maxY, minY), 1);
        int dimZ = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
        long volumeLong = (long) dimX * dimY * dimZ;
        if (volumeLong > MAX_DENSE_GRID_CELLS) {
            // A few widely separated emitter clusters can make their dense bounding box mostly empty.
            // Fall back to the global proposal rather than allocating an unexpectedly huge sparse map.
            return null;
        }
        int volume = (int) volumeLong;
        int[] cellOffsets = new int[volume];
        int[] cellCounts = new int[volume];
        float[] cellInvWeightSums = new float[volume];
        long spanCountLong = 0L;
        for (int sourceIndex = 0; sourceIndex < batches.size(); sourceIndex++) {
            if ((sourceIndex & 63) == 0) checkCancelled(cancelled);
            BatchLights source = batches.get(sourceIndex);
            if (!(source.power > 0.0)) continue;
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        int linear = ((source.z + dz - minZ) * dimY
                                + (source.y + dy - minY)) * dimX + (source.x + dx - minX);
                        cellCounts[linear]++;
                        spanCountLong++;
                    }
                }
            }
        }
        if (spanCountLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("Light grid span count exceeds Java array capacity: " + spanCountLong);
        }

        int populatedCells = 0;
        int maxCellSpans = 0;
        int prefix = 0;
        for (int cell = 0; cell < volume; cell++) {
            cellOffsets[cell] = prefix;
            int count = cellCounts[cell];
            prefix += count;
            if (count != 0) {
                populatedCells++;
                maxCellSpans = Math.max(maxCellSpans, count);
            }
        }
        int[] spanFirstLights = new int[prefix];
        int[] spanLightCounts = new int[prefix];
        int[] spanAliasFirstLights = new int[prefix];
        int[] spanAliasLightCounts = new int[prefix];
        float[] spanAccept = new float[prefix];
        double[] spanWeights = new double[prefix];
        int[] writeOffsets = cellOffsets.clone();

        // Batches arrive sorted by first light. Appending sources in that order means every cell's
        // span range is already sorted, avoiding 125-element per-cell lists and sorts.
        for (int sourceIndex = 0; sourceIndex < batches.size(); sourceIndex++) {
            if ((sourceIndex & 63) == 0) checkCancelled(cancelled);
            BatchLights source = batches.get(sourceIndex);
            if (!(source.power > 0.0)) continue;
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        int linear = ((source.z + dz - minZ) * dimY
                                + (source.y + dy - minY)) * dimX + (source.x + dx - minX);
                        int span = writeOffsets[linear]++;
                        int distanceSq = dx * dx + dy * dy + dz * dz;
                        spanFirstLights[span] = source.firstLight;
                        spanLightCounts[span] = source.lightCount;
                        // The common 16^2 world-to-cell scale cancels during normalization. Keeping
                        // this in cell units lets the shader reconstruct the same weight in O(1).
                        spanWeights[span] = source.power / Math.max(1, distanceSq);
                    }
                }
            }
        }

        // Vose alias-table construction (weights, small/large scratch, accept/alias write-back) is
        // identical to the batch-local aliases the scene builder creates; share that implementation
        // instead of a second copy of the same 40-odd lines.
        int[] spanAliasIndex = new int[prefix];
        RtRetainedLightSceneBuilder.AliasScratch scratch = new RtRetainedLightSceneBuilder.AliasScratch(maxCellSpans);
        for (int cell = 0; cell < volume; cell++) {
            if ((cell & 4095) == 0) checkCancelled(cancelled);
            int first = cellOffsets[cell];
            int count = cellCounts[cell];
            if (count == 0) continue;
            double totalWeight = RtRetainedLightSceneBuilder.buildAliasInto(spanWeights, first, count,
                    spanAliasIndex, spanAccept, first, scratch, cancelled);
            cellInvWeightSums[cell] = (float) (1.0 / totalWeight);
            for (int i = 0; i < count; i++) {
                int aliasSpan = first + spanAliasIndex[first + i];
                spanAliasFirstLights[first + i] = spanFirstLights[aliasSpan];
                spanAliasLightCounts[first + i] = spanLightCounts[aliasSpan];
            }
        }

        return new Data(minX * 16 - rebaseX, minY * 16 - rebaseY, minZ * 16 - rebaseZ,
                dimX, dimY, dimZ, cellOffsets, cellCounts, cellInvWeightSums,
                spanFirstLights, spanLightCounts, spanAliasFirstLights, spanAliasLightCounts,
                spanAccept, populatedCells, spanCountLong);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Superseded light grid build");
    }

    record BatchLights(int firstLight, int lightCount, int x, int y, int z, double power) {
    }

    record Data(int originX, int originY, int originZ, int dimX, int dimY, int dimZ,
                int[] cellOffsets, int[] cellCounts, float[] cellInvWeightSums,
                int[] spanFirstLights, int[] spanLightCounts,
                int[] spanAliasFirstLights, int[] spanAliasLightCounts,
                float[] spanAccept, int populatedCells, long representedBatches) {
        long cellBytes() {
            return Math.multiplyExact((long) cellOffsets.length, 12L);
        }

        long spanBytes() {
            return Math.multiplyExact((long) spanFirstLights.length, 16L);
        }
    }
}
