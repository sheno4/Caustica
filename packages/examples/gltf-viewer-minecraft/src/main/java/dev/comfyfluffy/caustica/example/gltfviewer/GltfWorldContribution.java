package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.geometry.ReadyMesh;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import java.util.concurrent.CompletableFuture;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfMeshUploader;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfPrimitiveUploader;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfProgramContent;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfProgramExports;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfScene;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Retains glTF primitive meshes and instances authored nodes at each loaded world anchor. */
final class GltfWorldContribution implements MinecraftWorldSessionContribution {
    private static final AtomicLong INDEX_REVISIONS = new AtomicLong();
    private final MinecraftWorldSessionContext context;
    private final GltfProgramExports programs;
    private final ProgramRegistration<GltfProgramExports> programRegistration;
    private final GltfViewerAssetRepository assets;
    private final GltfPrimitiveUploader uploader;
    private final Supplier<Set<BlockPos>> gltfAnchors;
    private final Supplier<Set<BlockPos>> portalAnchors;
    private Live live = Live.EMPTY;
    private boolean stopped;
    private long request;

    static GltfWorldContribution open(MinecraftWorldSessionContext context) {
        ProgramRegistration<GltfProgramExports> registration =
                GltfProgramContent.register(context.renderSession().program());
        GltfViewerAssetRepository assets = new GltfViewerAssetRepository();
        try {
            GltfWorldContribution contribution = new GltfWorldContribution(context, registration, assets,
                    new GltfMeshUploader(context.renderSession().gpu()),
                    () -> loadedAnchors(GltfViewerBlocks.GLTF_ANCHOR),
                    () -> loadedAnchors(GltfViewerBlocks.PROCEDURAL_SURFACE));
            contribution.replace();
            return contribution;
        } catch (RuntimeException | Error failure) {
            registration.close();
            assets.clear();
            throw failure;
        }
    }

    GltfWorldContribution(MinecraftWorldSessionContext context,
                          ProgramRegistration<GltfProgramExports> programRegistration,
                          GltfViewerAssetRepository assets, GltfPrimitiveUploader uploader,
                          Supplier<Set<BlockPos>> gltfAnchors, Supplier<Set<BlockPos>> portalAnchors) {
        this.context = context;
        this.programRegistration = programRegistration;
        this.programs = programRegistration.exports();
        this.assets = assets;
        this.uploader = uploader;
        this.gltfAnchors = gltfAnchors;
        this.portalAnchors = portalAnchors;
    }

    @Override
    public void resourcePackChanged(ResourcePackEpoch epoch) {
        if (stopped) throw new IllegalStateException("glTF world contribution is stopped");
        replace();
    }

    private synchronized void replace() {
        assets.reload();
        long preparing = ++request;
        GltfScene authored = assets.current();
        var anchors = Set.copyOf(gltfAnchors.get());
        var portals = Set.copyOf(portalAnchors.get());
        var pending = new ArrayList<CompletableFuture<ReadyMesh<GltfProgramExports.InstanceData>>>();
        try {
            for (var primitive : authored.primitives()) pending.add(prepare(primitive, false));
            pending.add(prepare(portalCube(), true));
        } catch (RuntimeException | Error failure) {
            pending.forEach(future -> future.thenAccept(ReadyMesh::close));
            throw failure;
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            var ready = pending.stream().filter(future -> !future.isCompletedExceptionally())
                    .map(CompletableFuture::join).toList();
            synchronized (this) {
                if (failure != null || stopped || preparing != request) {
                    ready.forEach(ReadyMesh::close);
                    if (failure != null && !stopped) reportFailure(failure);
                    return;
                }
                var edits = context.renderSession().scene();
                var operations = dropOperations(live);
                var instances = new ArrayList<InstanceId>();
                for (BlockPos anchor : anchors) {
                    for (var placement : authored.placements()) {
                        var instance = edits.newInstance();
                        instances.add(instance);
                        operations.add(new SceneEdit.SetInstance<>(instance, context.scene(),
                                ready.get(placement.primitive()),
                                placement.at(anchor.getX(), anchor.getY(), anchor.getZ()), 0xff,
                                GltfProgramExports.INSTANCE.data(0L)));
                    }
                }
                for (BlockPos anchor : portals) {
                    var instance = edits.newInstance();
                    instances.add(instance);
                    operations.add(new SceneEdit.SetInstance<>(instance, context.scene(), ready.getLast(),
                            GeometryTransform.translation(anchor.getX(), anchor.getY(), anchor.getZ()),
                            0xff, GltfProgramExports.INSTANCE.data(0L)));
                }
                try { edits.edit(operations); }
                catch (RuntimeException | Error rejected) {
                    ready.forEach(ReadyMesh::close);
                    reportFailure(rejected);
                    return;
                }
                Live previous = live;
                live = new Live(ready, List.copyOf(instances));
                previous.close();
            }
        });
    }

    private CompletableFuture<ReadyMesh<GltfProgramExports.InstanceData>> prepare(
            GltfScene.Primitive primitive, boolean portal) {
        var upload = uploader.upload(context.renderSession().resources(), primitive);
        try {
            MeshBuild.CoveragePolicy coverage = primitive.cutout()
                    ? new MeshBuild.CoveragePolicy.Cutout(primitive.alphaCutoff())
                    : new MeshBuild.CoveragePolicy.Opaque();
            var slot = new MeshBuild.SurfaceSlot<>(portal ? programs.portal() : programs.material(),
                    GltfProgramExports.PRIMITIVE.data(upload.primitiveDataAddress().value(),
                            upload.primitiveDataResource()), coverage);
            var build = new MeshBuild<>(upload.positionsStream(), upload.indexStream(), upload.vertexCount(),
                    new MeshBuild.IndexRevision(INDEX_REVISIONS.incrementAndGet()), MeshBuild.BuildPolicy.STATIC,
                    List.of(new MeshBuild.Geometry<>(slot, null, 0, upload.indexCount())));
            return context.renderSession().meshes().prepare(GltfProgramExports.INSTANCE, build);
        } finally {
            upload.drop();
        }
    }

    private static void reportFailure(Throwable failure) {
        Minecraft.getInstance().execute(() -> { throw new IllegalStateException("glTF preparation failed", failure); });
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        request++;
        try {
            context.renderSession().scene().edit(dropOperations(live));
            live.close();
            live = Live.EMPTY;
        } finally {
            programRegistration.close();
        }
    }

    @Override public void close() { assets.clear(); }

    private static List<SceneEdit> dropOperations(Live state) {
        return new ArrayList<>(state.instances.stream().map(SceneEdit.DropInstance::new)
                .map(SceneEdit.class::cast).toList());
    }

    private static GltfScene.Primitive portalCube() {
        return new GltfScene.Primitive(new float[]{
                0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
                0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1}, new int[]{
                0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
                0, 1, 5, 0, 5, 4, 3, 7, 6, 3, 6, 2,
                0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5},
                0.002f, 0.001f, 0.006f, 1.0f, 0.42f, 0.0f, false, 0.0f);
    }

    private static Set<BlockPos> loadedAnchors(net.minecraft.world.level.block.Block block) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? Set.of()
                : GltfViewerAnchorBlockEntity.loadedAnchors(minecraft.level, block);
    }

    private record Live(List<ReadyMesh<GltfProgramExports.InstanceData>> meshes, List<InstanceId> instances) {
        private static final Live EMPTY = new Live(List.of(), List.of());
        void close() { meshes.forEach(ReadyMesh::close); }
    }
}
