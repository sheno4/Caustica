package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftSceneReset;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Publishes one API-authored cube at every procedural-surface anchor. */
public final class ProceduralSurfaceSceneProvider implements SceneProvider {
    public static final ResourceId ID = ResourceId.of(GltfViewerMod.MOD_ID, "procedural_surface_anchors");
    private static final SceneGeometryKey GROUP = SceneGeometryKey.of(0L);
    private static final SceneGeometryKey RESIDENT = SceneGeometryKey.of(0L);
    private static final SceneMesh MESH = cube();

    private final Supplier<Set<BlockPos>> anchors;
    private final Map<Long, SceneGeometryKey> instanceKeys = new HashMap<>();
    private Set<Long> submitted = Set.of();
    private long nextInstanceKey;
    private boolean residentSubmitted;
    private SceneScope scope;
    private MinecraftSceneReset.Registration sceneReset = () -> { };

    public ProceduralSurfaceSceneProvider() {
        this(ProceduralSurfaceSceneProvider::loadedAnchors);
    }

    ProceduralSurfaceSceneProvider(Supplier<Set<BlockPos>> anchors) {
        this.anchors = anchors;
    }

    @Override
    public void onSessionStart(SceneScope scope) {
        reset();
        this.scope = scope;
        sceneReset.close();
        sceneReset = MinecraftSceneReset.register(scope, this::reset);
    }

    @Override
    public void prepareFrame() {
        Set<Long> positions = new LinkedHashSet<>();
        for (BlockPos position : anchors.get()) {
            positions.add(position.asLong());
        }
        publish(Set.copyOf(positions));
    }

    private void publish(Set<Long> visible) {
        if (visible.equals(submitted)) {
            return;
        }
        List<SceneGeometrySink.Operation> operations = new ArrayList<>();
        if (!residentSubmitted && !visible.isEmpty()) {
            operations.add(new SceneGeometrySink.Put(RESIDENT, MESH));
            residentSubmitted = true;
        }
        for (long packedPosition : visible) {
            if (!submitted.contains(packedPosition)) {
                BlockPos position = BlockPos.of(packedPosition);
                SceneGeometryKey instance = instanceKeys.computeIfAbsent(
                        packedPosition, ignored -> SceneGeometryKey.of(nextInstanceKey++));
                operations.add(new SceneGeometrySink.Place(instance, RESIDENT,
                        GeometryTransform.translation(position.getX(), position.getY(), position.getZ()), 0xff));
            }
        }
        for (long packedPosition : submitted) {
            if (!visible.contains(packedPosition)) {
                operations.add(new SceneGeometrySink.Remove(instanceKeys.remove(packedPosition)));
            }
        }
        if (visible.isEmpty() && residentSubmitted) {
            operations.add(new SceneGeometrySink.Drop(RESIDENT));
            residentSubmitted = false;
        }
        submitted = visible;
        scope.submit(GROUP, operations, () -> { });
    }

    @Override
    public void stop() {
        sceneReset.close();
        sceneReset = () -> { };
    }

    private void reset() {
        submitted = Set.of();
        instanceKeys.clear();
        nextInstanceKey = 0L;
        residentSubmitted = false;
    }

    private static SceneMesh cube() {
        float[] positions = {
                0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
                0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1
        };
        int[] indices = {
                0, 2, 1, 0, 3, 2,
                4, 5, 6, 4, 6, 7,
                0, 1, 5, 0, 5, 4,
                3, 7, 6, 3, 6, 2,
                0, 4, 7, 0, 7, 3,
                1, 2, 6, 1, 6, 5
        };
        SceneMesh.TriangleSurface surface = SceneMesh.TriangleSurface.surface(
                new MaterialHandle(GltfViewerExtension.PROCEDURAL_MATERIAL));
        return new SceneMesh(positions, indices, SceneMesh.UvLayout.PER_VERTEX, new float[16],
                Collections.nCopies(indices.length / 3, surface));
    }

    private static Set<BlockPos> loadedAnchors() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? Set.of() : GltfViewerAnchorBlockEntity.loadedAnchors(
                minecraft.level, GltfViewerBlocks.PROCEDURAL_SURFACE);
    }
}
