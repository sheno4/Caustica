package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyAtmosphereDimensionsTest {
    @Test void sourceAspectSizingUsesTruncationAndRetainsOneTexelThinViews() {
        assertEquals(new CloudlyAtmosphereDimensions.Extent(192, 111, 64),
                CloudlyAtmosphereDimensions.aerialPerspective(2562, 1486, 192, 64));
        assertEquals(new CloudlyAtmosphereDimensions.Extent(108, 192, 64),
                CloudlyAtmosphereDimensions.aerialPerspective(1080, 1920, 192, 64));
        assertEquals(new CloudlyAtmosphereDimensions.Extent(1, 192, 64),
                CloudlyAtmosphereDimensions.aerialPerspective(1, 10000, 192, 64));
        assertEquals(new CloudlyAtmosphereDimensions.Extent(8, 8, 64),
                CloudlyAtmosphereDimensions.aerialPerspective(0, 1080, 192, 64));
        assertArrayEquals(new int[]{24, 14, 1},
                CloudlyAtmosphereDimensions.aerialPerspective(2562, 1486, 192, 64).groupsXY(8, 8));
        assertThrows(IllegalArgumentException.class, () -> CloudlyAtmosphereDimensions.aerialPerspective(-1, 10, 192, 64));
    }
}
