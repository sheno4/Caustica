package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
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

    private void replace() {
        assets.reload();
        GeometryChannel geometry = context.renderSession().geometry();
        List<GeometryChannel.Operation> operations = dropOperations(live);
        List<GltfPrimitiveUploader.Uploaded> uploads = new ArrayList<>();
        List<MeshId<GltfProgramExports.InstanceData>> meshes = new ArrayList<>();
        List<InstanceId> instances = new ArrayList<>();
        try {
            GltfScene scene = assets.current();
            for (GltfScene.Primitive primitive : scene.primitives()) {
                GltfPrimitiveUploader.Uploaded upload = uploader.upload(primitive);
                uploads.add(upload);
                MeshId<GltfProgramExports.InstanceData> mesh = geometry.newMesh(GltfProgramExports.INSTANCE);
                meshes.add(mesh);
                MeshBuild.CoveragePolicy coverage = primitive.cutout()
                        ? new MeshBuild.CoveragePolicy.Cutout(primitive.alphaCutoff(), null)
                        : new MeshBuild.CoveragePolicy.Opaque();
                MeshBuild.SurfaceSlot<GltfProgramExports.PrimitiveData, GltfProgramExports.InstanceData> slot =
                        new MeshBuild.SurfaceSlot<>(programs.material(),
                                GltfProgramExports.PRIMITIVE.data(upload.primitiveDataAddress()), coverage);
                MeshBuild<GltfProgramExports.InstanceData> build = new MeshBuild<>(
                        upload.positionsStream(), null, upload.indexStream(), upload.vertexCount(),
                        new MeshBuild.IndexRevision(INDEX_REVISIONS.incrementAndGet()),
                        List.of(new MeshBuild.Geometry<>(slot, null, 0, upload.indexCount())));
                operations.add(new GeometryChannel.SetMesh<>(mesh, build));
            }
            for (BlockPos anchor : Set.copyOf(gltfAnchors.get())) {
                for (GltfScene.Placement placement : scene.placements()) {
                    InstanceId instance = geometry.newInstance();
                    instances.add(instance);
                    operations.add(new GeometryChannel.SetInstance<>(instance, context.scene(),
                            meshes.get(placement.primitive()),
                            placement.at(anchor.getX(), anchor.getY(), anchor.getZ()), 0xff,
                            GltfProgramExports.INSTANCE.data(0L)));
                }
            }

            GltfScene.Primitive portal = portalCube();
            GltfPrimitiveUploader.Uploaded portalUpload = uploader.upload(portal);
            uploads.add(portalUpload);
            MeshId<GltfProgramExports.InstanceData> portalMesh = geometry.newMesh(GltfProgramExports.INSTANCE);
            meshes.add(portalMesh);
            var portalSlot = new MeshBuild.SurfaceSlot<>(programs.portal(),
                    GltfProgramExports.PRIMITIVE.data(portalUpload.primitiveDataAddress()),
                    new MeshBuild.CoveragePolicy.Opaque());
            operations.add(new GeometryChannel.SetMesh<>(portalMesh, new MeshBuild<>(
                    portalUpload.positionsStream(), null, portalUpload.indexStream(), portalUpload.vertexCount(),
                    new MeshBuild.IndexRevision(INDEX_REVISIONS.incrementAndGet()),
                    List.of(new MeshBuild.Geometry<>(portalSlot, null, 0, portalUpload.indexCount())))));
            for (BlockPos anchor : Set.copyOf(portalAnchors.get())) {
                InstanceId instance = geometry.newInstance();
                instances.add(instance);
                operations.add(new GeometryChannel.SetInstance<>(instance, context.scene(), portalMesh,
                        GeometryTransform.translation(anchor.getX(), anchor.getY(), anchor.getZ()), 0xff,
                        GltfProgramExports.INSTANCE.data(0L)));
            }

            Live next = new Live(List.copyOf(meshes), List.copyOf(instances));
            geometry.submit(new RetainedBatch<>(operations, () -> uploads.forEach(
                    GltfPrimitiveUploader.Uploaded::destroy)));
            live = next;
        } catch (RuntimeException | Error failure) {
            uploads.forEach(GltfPrimitiveUploader.Uploaded::destroy);
            throw failure;
        }
    }

    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        try {
            if (live != Live.EMPTY) {
                context.renderSession().geometry().submit(RetainedBatch.of(dropOperations(live)));
                live = Live.EMPTY;
            }
        } finally {
            programRegistration.close();
        }
    }

    @Override public void close() { assets.clear(); }

    private static List<GeometryChannel.Operation> dropOperations(Live state) {
        List<GeometryChannel.Operation> operations = new ArrayList<>();
        state.instances.forEach(instance -> operations.add(new GeometryChannel.DropInstance(instance)));
        state.meshes.forEach(mesh -> operations.add(new GeometryChannel.DropMesh<>(mesh)));
        return operations;
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

    private record Live(List<MeshId<GltfProgramExports.InstanceData>> meshes, List<InstanceId> instances) {
        private static final Live EMPTY = new Live(List.of(), List.of());
    }
}
