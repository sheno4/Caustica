package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;
import static dev.comfyfluffy.caustica.minecraft.rendering.terrain.TerrainOpacityBaker.*;

class TerrainOpacityBakerTest {
    @Test void birdCurveVisitsEveryMicrotriangleExactlyOnce() {
        for (int level = 0; level <= 5; level++) {
            int grid = 1 << level;
            boolean[] visited = new boolean[grid * grid];
            for (int y = 0; y < grid; y++) for (int x = 0; x < grid - y; x++) {
                int lower = TerrainOpacityBaker.birdIndex(x, y, false, level);
                assertFalse(visited[lower]);
                visited[lower] = true;
                if (x + y < grid - 1) {
                    int upper = TerrainOpacityBaker.birdIndex(x, y, true, level);
                    assertFalse(visited[upper]);
                    visited[upper] = true;
                }
            }
            for (boolean value : visited) assertTrue(value);
        }
        // Vulkan subdivision level one: origin, center, U corner, V corner.
        assertEquals(0, TerrainOpacityBaker.birdIndex(0, 0, false, 1));
        assertEquals(1, TerrainOpacityBaker.birdIndex(0, 0, true, 1));
        assertEquals(2, TerrainOpacityBaker.birdIndex(1, 0, false, 1));
        assertEquals(3, TerrainOpacityBaker.birdIndex(0, 1, false, 1));
    }

    @Test void geometrySliceKeepsTriangleOrderAndByteBoundaries() {
        float[] uv = {0,0, 1,0, 0,1,  0,0, 1,0, 0,1,  0,0, 1,0, 0,1};
        var map = bake(uv, 1, 2, (triangle, a,b,c,d) -> triangle == 1 ? OPAQUE : TRANSPARENT);
        assertEquals(2, map.triangleCount());
        var bytes = ByteBuffer.allocate(map.byteSize());
        map.write(bytes);
        for (int i = 0; i < map.bytesPerTriangle(); i++) assertEquals(0x55, bytes.get(i) & 255);
        for (int i = map.bytesPerTriangle(); i < map.byteSize(); i++) assertEquals(0, bytes.get(i));
        assertNull(bake(uv, 0, 3, (t,a,b,c,d) -> UNKNOWN));
    }

    @Test void bilinearNeighborsAnimationAndAtlasEdgesRemainConservative() {
        assertEquals(OPAQUE, classifyRegion(16,16,2, (f,x,y) -> 255, .3f,.3f,.5f,.5f,.5f));
        assertEquals(TRANSPARENT, classifyRegion(16,16,2, (f,x,y) -> 0, .3f,.3f,.5f,.5f,.5f));
        assertEquals(UNKNOWN, classifyRegion(16,16,2, (f,x,y) -> f * 255, .3f,.3f,.5f,.5f,.5f));
        assertEquals(UNKNOWN, classifyRegion(16,16,1, (f,x,y) -> x == 3 ? 0 : 255,
                .25f,.3f,.26f,.4f,.5f));
        assertEquals(UNKNOWN, classifyRegion(16,16,1, (f,x,y) -> 255, 0,0,.2f,.2f,.5f));
        assertEquals(OPAQUE, classifyRegion(16,16,1, (f,x,y) -> 128, .3f,.3f,.5f,.5f,128 / 255f));
        assertEquals(TRANSPARENT, classifyRegion(16,16,1, (f,x,y) -> 127, .3f,.3f,.5f,.5f,128 / 255f));
    }

    @Test void knownStatesAgreeWithDenseBilinearSamplesInsideTheirRectangles() {
        var random = new java.util.Random(4819);
        for (int trial = 0; trial < 100; trial++) {
            int[] pixels = new int[256];
            for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt(256);
            float u0 = .1f + random.nextFloat() * .6f, v0 = .1f + random.nextFloat() * .6f;
            float u1 = u0 + .07f, v1 = v0 + .07f;
            for (float cutoff : new float[]{0, .2f, .5f, .8f, 1}) {
                int state = classifyRegion(16,16,1,(f,x,y) -> pixels[y * 16 + x],u0,v0,u1,v1,cutoff);
                if (state == UNKNOWN) continue;
                for (int j = 0; j <= 10; j++) for (int i = 0; i <= 10; i++) {
                    float x = (u0 + (u1 - u0) * i / 10f) * 16 - .5f;
                    float y = (v0 + (v1 - v0) * j / 10f) * 16 - .5f;
                    int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
                    float fx = x - ix, fy = y - iy;
                    float alpha = ((1-fy) * ((1-fx)*pixels[iy*16+ix] + fx*pixels[iy*16+ix+1])
                            + fy * ((1-fx)*pixels[(iy+1)*16+ix] + fx*pixels[(iy+1)*16+ix+1])) / 255f;
                    assertEquals(state == OPAQUE, alpha >= cutoff);
                }
            }
        }
    }
}
