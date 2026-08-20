package dev.comfyfluffy.caustica.api.provider;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque material data written by a provider and consumed only by its registered shader implementation.
 * Every word preserves its complete unsigned 32-bit bit pattern; the renderer copies the words without
 * assigning them a meaning. These twelve words are available to shading only; coverage implementations
 * receive generic base-texture, UV, tint, and vertex-color facts instead of this provider blob.
 */
public final class MaterialProviderData {
    public static final int WORD_COUNT = 12;
    public static final MaterialProviderData ZERO = new MaterialProviderData(new int[WORD_COUNT]);

    private final int[] words;

    public MaterialProviderData(int[] words) {
        Objects.requireNonNull(words, "words");
        if (words.length != WORD_COUNT) {
            throw new IllegalArgumentException("material provider data requires exactly " + WORD_COUNT + " words");
        }
        this.words = words.clone();
    }

    public int word(int index) {
        return words[index];
    }

    public int[] words() {
        return words.clone();
    }

    @Override
    public boolean equals(Object value) {
        return value == this || value instanceof MaterialProviderData other
                && Arrays.equals(words, other.words);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(words);
    }

    @Override
    public String toString() {
        return "MaterialProviderData" + Arrays.toString(words);
    }
}
