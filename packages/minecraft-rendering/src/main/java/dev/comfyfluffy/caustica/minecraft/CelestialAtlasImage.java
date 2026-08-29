package dev.comfyfluffy.caustica.minecraft;

/** Borrowed RGBA8_UNORM 2D Vulkan image whose retained descriptor views must be released exactly once. */
public interface CelestialAtlasImage {
    long vkImage();

    void retainViews();

    void releaseViews();
}
