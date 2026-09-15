package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.minecraft.content.material.*;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class TerrainOpacityCacheTest {
    @Test void repeatedSlicesMaterialsCutoffsAndEvictionMatchDirectBaking() throws Exception {
        var cache = new TerrainOpacityCache();
        var bounds = new MinecraftOpacityBounds[]{bounds(0), bounds(1), bounds(2), null};
        var random = new Random(7812);
        Object epoch = new Object();
        cache.beginEpoch(epoch);
        for (int trial = 0; trial < 4300; trial++) {
            float[] uv = new float[18];
            for (int i = 0; i < uv.length; i += 2) {
                uv[i] = .25f + random.nextFloat() * .25f;
                uv[i + 1] = .5f + random.nextFloat() * .25f;
            }
            int variant = trial % bounds.length;
            float cutoff = new float[]{0, .2f, .5f, .8f, 1}[trial % 5];
            java.util.function.IntFunction<MinecraftOpacityBounds> materials = t -> bounds[(variant + t) % bounds.length];
            var expected = TerrainOpacityBaker.bake(uv, 1, 2, (t, a, b, c, d) -> {
                var material = materials.apply(t);
                if (material == null) return TerrainOpacityBaker.UNKNOWN;
                var transform = material.uv();
                return TerrainOpacityBaker.classifyRegion(material.width(), material.height(), 2, material::alpha,
                        (a - transform.u()) * transform.inverseDu(), (b - transform.v()) * transform.inverseDv(),
                        (c - transform.u()) * transform.inverseDu(), (d - transform.v()) * transform.inverseDv(), cutoff);
            });
            assertEquals(expected, cache.bake(uv, 1, 2, materials, cutoff));
            assertEquals(expected, cache.bake(uv, 1, 2, materials, cutoff));
            if (trial == 4200) cache.beginEpoch(new Object());
        }
    }

    @Test void identicalUvsKeepDifferentMaterialAndCutoffCoverage() throws Exception {
        var cache = new TerrainOpacityCache();
        float[] uv = {.28f,.53f, .33f,.53f, .28f,.58f};
        var transparent = bounds(0);
        var opaque = bounds(1);
        var a = cache.bake(uv, 0, 1, t -> transparent, .5f);
        var b = cache.bake(uv, 0, 1, t -> opaque, .5f);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b);
        assertEquals(b, cache.bake(uv, 0, 1, t -> transparent, 0));
        assertEquals(a, cache.bake(uv, 0, 1, t -> transparent, .5f));
        assertNull(cache.bake(uv, 0, 1, t -> null, .5f));
    }

    private static MinecraftOpacityBounds bounds(int variant) throws Exception {
        MaterialTextureSource source = () -> new MaterialTextureImage() {
            public int width() { return 16; }
            public int height() { return 16; }
            public int albedoArgb(int x, int y) { return alphaArgb(0, x, y); }
            public int alphaArgb(int frame, int x, int y) {
                int alpha = variant == 0 ? 0 : variant == 1 ? 255 : (x / 4 + y / 4 + frame) % 3 * 127;
                return alpha << 24;
            }
            public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
            public void close() { }
        };
        return MinecraftOpacityBounds.scan(new MaterialTextureResource(ResourceId.of("test", "cache"),
                MaterialTextureKind.SHARED_ATLAS, new MaterialTextureAnalysisSource(16, 16, 2, source),
                new MaterialUv(.25f, .5f, 4, 4), false, false, false,
                OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR, 1.5f, 0));
    }
}
