package dev.comfyfluffy.caustica.api.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SceneGeometryBuildOptionsTest {
    @Test
    void legacyPutConstructorKeepsMemoryOptimizationDisabled() {
        SceneGeometrySink.Put put = new SceneGeometrySink.Put(7L, triangle());

        assertSame(SceneGeometrySink.BuildOptions.DEFAULT, put.buildOptions());
        assertFalse(put.buildOptions().minimizeMemory());
        assertFalse(put.buildOptions().opacityAcceleration());
    }

    @Test
    void memoryOptimizationIsSelectedPerPut() {
        SceneGeometrySink.Put put = new SceneGeometrySink.Put(7L, triangle(),
                SceneGeometrySink.BuildOptions.MINIMIZE_MEMORY);

        assertTrue(put.buildOptions().minimizeMemory());
        assertFalse(put.buildOptions().opacityAcceleration());
    }

    @Test
    void opacityAccelerationIsAnIndependentEnginePreference() {
        SceneGeometrySink.BuildOptions options =
                SceneGeometrySink.BuildOptions.MINIMIZE_MEMORY_AND_ACCELERATE_OPACITY;
        assertTrue(options.minimizeMemory());
        assertTrue(options.opacityAcceleration());
    }

    private static SceneMesh triangle() {
        MaterialHandle material = MaterialHandle.of("test", "material");
        return new SceneMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX, new float[]{0, 0, 1, 0, 0, 1},
                List.of(SceneMesh.TriangleSurface.surface(material)));
    }
}
