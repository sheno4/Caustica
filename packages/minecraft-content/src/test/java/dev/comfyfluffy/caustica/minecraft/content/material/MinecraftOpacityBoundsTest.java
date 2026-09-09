package dev.comfyfluffy.caustica.minecraft.content.material;

import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftOpacityBoundsTest {
    @Test void capturesAllFramesAndOutlivesTheSourceImage() throws Exception {
        int[][] pixels = {{0,255},{255,128},{64,192}};
        var closes = new AtomicInteger();
        MaterialTextureSource texture = () -> new MaterialTextureImage() {
            public int width() { return 2; }
            public int height() { return 1; }
            public int albedoArgb(int x,int y) { return pixels[0][x] << 24; }
            public int alphaArgb(int frame,int x,int y) { return pixels[frame][x] << 24; }
            public void readOpenPbr(int x,int y,OpenPbrTextureTexel out) { }
            public void close() { closes.incrementAndGet(); }
        };
        var resource = resource(new MaterialTextureAnalysisSource(2,1,3,texture));
        var bounds = MinecraftOpacityBounds.scan(resource);
        assertEquals(1,closes.get());
        assertEquals(0,bounds.alpha(0,0,0));
        assertEquals(255,bounds.alpha(1,0,0));
        assertEquals(128,bounds.alpha(0,1,0));
        assertEquals(255,bounds.alpha(1,1,0));
        pixels[0][1] = 0;
        assertEquals(128,bounds.alpha(0,1,0));
        assertSame(resource.albedoUv(),bounds.uv());
    }

    @Test void unknownAnimationCoverageDoesNotOpenOrBakeTheSource() throws Exception {
        var resource = resource(new MaterialTextureAnalysisSource(2,1,0,() -> {
            throw new AssertionError("unknown alpha must not be baked");
        }));
        assertNull(MinecraftOpacityBounds.scan(resource));
    }

    private static MaterialTextureResource resource(MaterialTextureAnalysisSource source) {
        return new MaterialTextureResource(ResourceId.of("test","sprite"),MaterialTextureKind.SHARED_ATLAS,source,
                new MaterialUv(.25f,.5f,4,4),false,false,false,
                OpenPbrColorBinding.BASE_COLOR,OpenPbrColorBinding.BASE_COLOR,1.5f,0);
    }
}
