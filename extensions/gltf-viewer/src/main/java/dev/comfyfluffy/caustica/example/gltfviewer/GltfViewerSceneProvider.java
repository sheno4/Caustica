package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.TextureSink;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Retains one copy of each glTF primitive and instances the authored scene at every loaded anchor. */
public final class GltfViewerSceneProvider implements SceneProvider {
    public static final ResourceId ID = ResourceId.of(GltfViewerMod.MOD_ID, "gltf_anchors");
    private static final SceneGeometryKey GROUP = SceneGeometryKey.of(0L);

    private final Supplier<GltfViewerScene> scenes;
    private final Supplier<Set<BlockPos>> anchors;
    private final Map<Instance, SceneGeometryKey> instanceKeys = new HashMap<>();
    private Set<Long> visible = Set.of();
    private Set<Long> submitted = Set.of();
    private long nextInstanceKey;
    private boolean residentsSubmitted;
    private boolean texturesSubmitted;

    public GltfViewerSceneProvider() {
        this(GltfViewerAssetRepository::current, GltfViewerSceneProvider::loadedAnchors);
    }

    GltfViewerSceneProvider(GltfViewerScene scene, Supplier<Set<BlockPos>> anchors) {
        this(() -> scene, anchors);
    }

    GltfViewerSceneProvider(Supplier<GltfViewerScene> scenes, Supplier<Set<BlockPos>> anchors) {
        this.scenes = scenes;
        this.anchors = anchors;
    }

    static GltfViewerScene model() {
        return GltfViewerAssetRepository.current();
    }

    @Override
    public void prepareFrame() {
        Set<Long> positions = new LinkedHashSet<>();
        for (BlockPos position : anchors.get()) {
            positions.add(position.asLong());
        }
        visible = Set.copyOf(positions);
    }

    @Override
    public void submitTextures(TextureSink sink) {
        if (texturesSubmitted) {
            return;
        }
        for (GltfViewerScene.Texture texture : scenes.get().textures()) {
            sink.submit(texture.reference(), texture.content());
        }
        texturesSubmitted = true;
    }

    @Override
    public void submitGeometry(SceneFrameContext frame) {
        if (visible.equals(submitted)) {
            return;
        }
        List<SceneGeometrySink.Operation> operations = new ArrayList<>();
        GltfViewerScene scene = scenes.get();
        if (!residentsSubmitted && !visible.isEmpty()) {
            for (GltfViewerScene.Resident resident : scene.residents()) {
                operations.add(new SceneGeometrySink.Put(resident.key(), resident.mesh()));
            }
            residentsSubmitted = true;
        }
        for (long packedPosition : visible) {
            if (submitted.contains(packedPosition)) {
                continue;
            }
            BlockPos position = BlockPos.of(packedPosition);
            for (int placementIndex = 0; placementIndex < scene.placements().size(); placementIndex++) {
                GltfViewerScene.Placement placement = scene.placements().get(placementIndex);
                SceneGeometryKey instanceKey = instanceKeys.computeIfAbsent(
                        new Instance(packedPosition, placementIndex), ignored -> SceneGeometryKey.of(nextInstanceKey++));
                operations.add(new SceneGeometrySink.Place(instanceKey, placement.resident(),
                        placement.at(position), 0xff));
            }
        }
        for (long packedPosition : submitted) {
            if (visible.contains(packedPosition)) {
                continue;
            }
            for (int placementIndex = 0; placementIndex < scene.placements().size(); placementIndex++) {
                Instance instance = new Instance(packedPosition, placementIndex);
                operations.add(new SceneGeometrySink.Remove(instanceKeys.remove(instance)));
            }
        }
        if (visible.isEmpty() && residentsSubmitted) {
            for (GltfViewerScene.Resident resident : scene.residents()) {
                operations.add(new SceneGeometrySink.Drop(resident.key()));
            }
            residentsSubmitted = false;
        }
        submitted = visible;
        frame.geometry().submit(GROUP, operations, () -> { });
    }

    @Override
    public void onWorldChanged() {
        reset(false);
    }

    @Override
    public void onResourcePackClosing() {
        reset(true);
        GltfViewerAssetRepository.clear();
    }

    @Override
    public void onResourcePackApplied() {
        GltfViewerAssetRepository.reload();
        reset(true);
    }

    private void reset(boolean resourcesClosing) {
        visible = Set.of();
        submitted = Set.of();
        residentsSubmitted = false;
        instanceKeys.clear();
        nextInstanceKey = 0L;
        if (resourcesClosing) {
            texturesSubmitted = false;
        }
    }

    private static Set<BlockPos> loadedAnchors() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? Set.of()
                : GltfViewerAnchorBlockEntity.loadedAnchors(minecraft.level, GltfViewerBlocks.GLTF_ANCHOR);
    }

    private record Instance(long anchor, int placement) {
    }
}
