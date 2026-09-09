package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class OpacityMicromapTest {
    @Test void dataIsImmutableAndComparedByContents() {
        byte[] source = {(byte) 0xe4};
        var map = new OpacityMicromap(1, 1, source);
        source[0] = 0;
        var bytes = ByteBuffer.allocate(1);
        map.write(bytes);
        assertEquals(0xe4, bytes.get(0) & 255);
        assertEquals(new OpacityMicromap(1, 1, new byte[]{(byte) 0xe4}), map);
        assertNotEquals(new OpacityMicromap(1, 1, source), map);
        assertThrows(IllegalArgumentException.class, () -> new OpacityMicromap(3,1,new byte[15]));
    }

    @Test void onlyMatchingCutoutSurfacesCanUseKnownOpacity() {
        var type = ShaderDataType.<Object>create("test");
        var data = type.data(0);
        var surface = new SurfaceId<Object, Object>() {};
        var volume = new MeshBuild.VolumeSlot<>(new VolumeId<Object,Object>() {}, data);
        var cutout = new MeshBuild.SurfaceSlot<>(surface, data, new MeshBuild.CoveragePolicy.Cutout(.5f));
        var map = new OpacityMicromap(0,1,new byte[]{1});
        assertSame(map, new MeshBuild.Geometry<>(cutout,null,0,3,map).opacityMicromap());
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild.Geometry<>(cutout,volume,0,3,map));
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild.Geometry<>(cutout,null,0,6,map));
        var stochastic = new MeshBuild.SurfaceSlot<>(surface, data, new MeshBuild.CoveragePolicy.Stochastic(.5f));
        assertThrows(IllegalArgumentException.class, () -> new MeshBuild.Geometry<>(stochastic,null,0,3,map));
    }
}
