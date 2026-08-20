package dev.comfyfluffy.caustica.minecraft.material;

/** Zero-copy view of canonical 8-bit ARGB pixels owned by a scene adapter. */
interface MaterialImage extends AutoCloseable {
    int width();

    int height();

    /** A pixel packed as {@code 0xAARRGGBB}. */
    int argb(int x, int y);

    @Override
    void close();
}
