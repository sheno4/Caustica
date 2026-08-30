package dev.comfyfluffy.caustica.minecraft.content.material;

import java.util.Arrays;
import java.util.List;

/** Immutable linear RGBA8 mip chain awaiting upload to the session material heap. */
public record MinecraftMaterialTexture(List<Mip> levels) {
    public MinecraftMaterialTexture {
        levels = List.copyOf(levels);
        if (levels.isEmpty()) throw new IllegalArgumentException("material texture needs at least one mip");
        for (int index = 1; index < levels.size(); index++) {
            Mip previous = levels.get(index - 1);
            Mip level = levels.get(index);
            if (level.width() != Math.max(1, previous.width() / 2)
                    || level.height() != Math.max(1, previous.height() / 2)) {
                throw new IllegalArgumentException("material texture mips must form a halving chain");
            }
        }
    }

    public record Mip(int width, int height, byte[] rgba8) {
        public Mip {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("mip dimensions must be positive");
            rgba8 = Arrays.copyOf(rgba8, rgba8.length);
            if (rgba8.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
                throw new IllegalArgumentException("mip payload must contain RGBA8 texels");
            }
        }

        @Override public byte[] rgba8() { return Arrays.copyOf(rgba8, rgba8.length); }
    }
}
