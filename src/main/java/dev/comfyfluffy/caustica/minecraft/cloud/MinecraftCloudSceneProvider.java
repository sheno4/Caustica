package dev.comfyfluffy.caustica.minecraft.cloud;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftSceneReset;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Minecraft camera window feeding deterministic rounded cloud meshes through the scene API. */
public final class MinecraftCloudSceneProvider implements SceneProvider {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_clouds");
    public static final MaterialHandle MATERIAL = MaterialHandle.of("caustica", "cloud");
    private static final int VARIANTS = 4;
    private static final int CELL_SIZE = 128;
    private static final int RADIUS = 2;
    private static final double CLOUD_HEIGHT = 192.0;
    private static final SceneMesh[] MESHES = createMeshes();
    private static final SceneGeometryKey SCENE_GROUP = SceneGeometryKey.of(0L);

    private int centerCellX;
    private int centerCellZ;
    private boolean visible;
    private final RetainedState retained = new RetainedState();
    private MinecraftSceneReset.Registration sceneReset = () -> { };

    @Override
    public void onSessionStart(SceneScope scope) {
        sceneReset.close();
        sceneReset = MinecraftSceneReset.register(scope, this::resetGeometry);
    }

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
    public void submitGeometry(SceneFrameContext frame) {
        SceneGeometrySink sink = frame.geometry();
        if (!visible) {
            RetainedState.Transition transition = retained.hidden();
            if (transition != null) {
                List<SceneGeometrySink.Operation> operations = new ArrayList<>(transition.previous().size() + MESHES.length);
                for (long instance : transition.previous()) operations.add(new SceneGeometrySink.Remove(instance));
                for (int variant = 0; variant < MESHES.length; variant++) {
                    operations.add(new SceneGeometrySink.Drop(variant));
                }
                sink.submit(SCENE_GROUP, operations, () -> retained.acknowledge(transition));
            }
            return;
        }
        Set<Long> desired = new LinkedHashSet<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int cellX = centerCellX + dx;
                int cellZ = centerCellZ + dz;
                desired.add(((long) cellX << 32) ^ (cellZ & 0xffffffffL));
            }
        }
        RetainedState.Transition transition = retained.visible(desired);
        if (transition == null) return;
        List<SceneGeometrySink.Operation> operations = new ArrayList<>();
        if (transition.putMeshes()) {
            for (int variant = 0; variant < MESHES.length; variant++) {
                operations.add(new SceneGeometrySink.Put(variant, MESHES[variant]));
            }
        }
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int cellX = centerCellX + dx;
                int cellZ = centerCellZ + dz;
                long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
                int variant = (int) (mix(key) & (VARIANTS - 1));
                if (transition.putMeshes() || !transition.previous().contains(key)) {
                    operations.add(new SceneGeometrySink.Place(key, variant, GeometryTransform.translation(
                            (double) cellX * CELL_SIZE + CELL_SIZE * 0.5,
                            CLOUD_HEIGHT + variant * 2.0,
                            (double) cellZ * CELL_SIZE + CELL_SIZE * 0.5)));
                }
            }
        }
        for (long instance : transition.previous()) {
            if (!desired.contains(instance)) operations.add(new SceneGeometrySink.Remove(instance));
        }
        sink.submit(SCENE_GROUP, operations, () -> retained.acknowledge(transition));
    }

    @Override
    public void onResourcePackClosing() {
        resetGeometry();
    }

    @Override
    public void onResourcePackApplied() {
        resetGeometry();
    }

    @Override
    public void stop() {
        sceneReset.close();
        sceneReset = () -> { };
    }

    private void resetGeometry() {
        retained.reset();
    }

    static final class RetainedState {
        private boolean desiredMeshes;
        private boolean publishedMeshes;
        private final Set<Long> desiredInstances = new LinkedHashSet<>();
        private final Set<Long> publishedInstances = new LinkedHashSet<>();

        Transition visible(Set<Long> next) {
            if (desiredMeshes && desiredInstances.equals(next)) return null;
            Set<Long> previous = Set.copyOf(desiredInstances);
            boolean putMeshes = !publishedMeshes || !desiredMeshes;
            desiredMeshes = true;
            desiredInstances.clear();
            desiredInstances.addAll(next);
            return new Transition(putMeshes, true, previous, Set.copyOf(next));
        }

        Transition hidden() {
            if (!desiredMeshes && desiredInstances.isEmpty()) return null;
            Set<Long> previous = Set.copyOf(desiredInstances);
            desiredMeshes = false;
            desiredInstances.clear();
            return new Transition(false, false, previous, Set.of());
        }

        void acknowledge(Transition transition) {
            publishedMeshes = transition.meshes();
            publishedInstances.clear();
            publishedInstances.addAll(transition.desired());
        }

        void reset() {
            desiredMeshes = false;
            publishedMeshes = false;
            desiredInstances.clear();
            publishedInstances.clear();
        }

        boolean desiredMeshes() { return desiredMeshes; }
        boolean publishedMeshes() { return publishedMeshes; }
        Set<Long> desiredInstances() { return Set.copyOf(desiredInstances); }
        Set<Long> publishedInstances() { return Set.copyOf(publishedInstances); }

        record Transition(boolean putMeshes, boolean meshes, Set<Long> previous, Set<Long> desired) { }
    }

    private static SceneMesh[] createMeshes() {
        SceneMesh[] meshes = new SceneMesh[VARIANTS];
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
