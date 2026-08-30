package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure rectangle planning for independently allocated material-page channels. */
final class MinecraftMaterialPagePlanner {
    static final int CHANNEL_MATERIAL = 1;
    static final int CHANNEL_EMISSION = 8;

    record Input(int index, String key, int width, int height, int channels) {
    }

    record Placement(int inputIndex, int pageIndex, int x, int y) {
    }

    record Layout(int channels) {
        boolean has(int channel) {
            return (channels & channel) != 0;
        }
    }

    record Plan(int pageSize, List<Layout> layouts, List<Placement> placements,
                boolean rejectedOversizedInput) {
        Placement placement(int inputIndex) {
            return placements.stream().filter(value -> value.inputIndex == inputIndex).findFirst().orElse(null);
        }
    }

    private static final class MutableLayout {
        final int size;
        int x;
        int y;
        int rowHeight;
        int channels;

        MutableLayout(int size, boolean reserveFallback, int gutter, int alignment) {
            this.size = size;
            if (reserveFallback) y = align(1 + 2 * gutter, alignment);
        }

        Placement place(Input input, int pageIndex, int gutter, int alignment) {
            int cellWidth = align(input.width + 2 * gutter, alignment);
            int cellHeight = align(input.height + 2 * gutter, alignment);
            if (cellWidth > size || cellHeight > size) return null;
            if (x + cellWidth > size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + cellHeight > size) return null;
            Placement placement = new Placement(input.index, pageIndex, x + gutter, y + gutter);
            channels |= input.channels;
            x += cellWidth;
            rowHeight = Math.max(rowHeight, cellHeight);
            return placement;
        }
    }

    private MinecraftMaterialPagePlanner() {
    }

    static Plan plan(List<Input> source, int defaultPageSize, int maxPageSize, int gutter, int alignment) {
        List<Input> inputs = source.stream().filter(input -> input.channels != 0)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean oversized = inputs.removeIf(input -> !eligible(input.width, input.height, maxPageSize, gutter));
        int largest = 1 + 2 * gutter;
        for (Input input : inputs) {
            largest = Math.max(largest, Math.max(input.width, input.height) + 2 * gutter);
        }
        int pageSize = inputs.isEmpty() ? 32
                : Math.min(maxPageSize, Math.max(defaultPageSize, nextPowerOfTwo(largest)));

        inputs.sort(Comparator.<Input>comparingInt(input ->
                        (input.channels & CHANNEL_MATERIAL) != 0 ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Input::height).reversed())
                .thenComparing(Comparator.comparingInt(Input::width).reversed())
                .thenComparing(Input::key)
                .thenComparingInt(Input::index));

        List<MutableLayout> mutableLayouts = new ArrayList<>();
        mutableLayouts.add(new MutableLayout(pageSize, true, gutter, alignment));
        List<Placement> placements = new ArrayList<>(inputs.size());
        for (Input input : inputs) {
            Placement placement = null;
            for (int pageIndex = 0; pageIndex < mutableLayouts.size(); pageIndex++) {
                placement = mutableLayouts.get(pageIndex).place(input, pageIndex, gutter, alignment);
                if (placement != null) break;
            }
            if (placement == null) {
                MutableLayout layout = new MutableLayout(pageSize, false, gutter, alignment);
                mutableLayouts.add(layout);
                placement = layout.place(input, mutableLayouts.size() - 1, gutter, alignment);
            }
            placements.add(placement);
        }
        List<Layout> layouts = mutableLayouts.stream().map(layout -> new Layout(layout.channels)).toList();
        return new Plan(pageSize, layouts, List.copyOf(placements), oversized);
    }

    static boolean eligible(int width, int height, int maxPageSize, int gutter) {
        int maximumContentExtent = maxPageSize - 2 * gutter;
        return width <= maximumContentExtent && height <= maximumContentExtent;
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }
}
