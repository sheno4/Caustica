package dev.comfyfluffy.caustica.minecraft.cloud;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Minecraft camera window feeding deterministic rounded cloud meshes through the scene API. */
public final class MinecraftCloudSceneProvider implements SceneProvider {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_clouds");
    public static final MaterialHandle MATERIAL = MaterialHandle.of("caustica", "cloud");
    private static final int VARIANTS = 4;
    private static final int CELL_SIZE = 128;
    private static final int RADIUS = 2;
    private static final double CLOUD_HEIGHT = 192.0;
    private static final TriangleMesh[] MESHES = createMeshes();

    private int centerCellX;
    private int centerCellZ;
    private boolean visible;

    @Override
    public void prepareFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        visible = minecraft.level != null && camera != null
                && Level.OVERWORLD.equals(minecraft.level.dimension());
        if (visible) {
            centerCellX = Math.floorDiv((int) Math.floor(camera.getX()), CELL_SIZE);
            centerCellZ = Math.floorDiv((int) Math.floor(camera.getZ()), CELL_SIZE);
        }
    }

    @Override
    public void submitGeometry(SceneGeometrySink sink) {
        if (!visible) {
            return;
        }
        for (int variant = 0; variant < MESHES.length; variant++) {
            sink.retainMesh(variant, MESHES[variant]);
        }
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int cellX = centerCellX + dx;
                int cellZ = centerCellZ + dz;
                long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
                int variant = (int) (mix(key) & (VARIANTS - 1));
                sink.instance(key, variant, GeometryTransform.translation(
                        (double) cellX * CELL_SIZE + CELL_SIZE * 0.5,
                        CLOUD_HEIGHT + variant * 2.0,
                        (double) cellZ * CELL_SIZE + CELL_SIZE * 0.5));
            }
        }
    }

    private static TriangleMesh[] createMeshes() {
        TriangleMesh[] meshes = new TriangleMesh[VARIANTS];
        for (int i = 0; i < meshes.length; i++) {
            meshes[i] = RoundedCloudMesh.generate(0xCA0571CAL + i * 0x9E3779B9L, MATERIAL);
        }
        return meshes;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
