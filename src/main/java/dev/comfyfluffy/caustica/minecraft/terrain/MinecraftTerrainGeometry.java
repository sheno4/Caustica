package dev.comfyfluffy.caustica.minecraft.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Direct retained-geometry owner for the sections of one borrowed Minecraft scene. */
public final class MinecraftTerrainGeometry implements AutoCloseable {
    private final GeometryChannel channel;
    private final SceneId scene;
    private final MinecraftTerrainUploader uploader;
    private final Map<Long, SectionIds> sections = new LinkedHashMap<>();
    private boolean closed;

    public MinecraftTerrainGeometry(GeometryChannel channel, SceneId scene, MinecraftTerrainUploader uploader) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
    }

    /** Atomically replaces and removes all sections named by one Minecraft extraction transaction. */
    public synchronized void submit(List<Change> changes) {
        if (closed) throw new IllegalStateException("terrain geometry is closed");
        if (changes.isEmpty()) return;
        var latest = new LinkedHashMap<Long, Change>();
        for (Change change : changes) latest.put(change.sectionKey(), change);

        var operations = new ArrayList<GeometryChannel.Operation>();
        var uploads = new ArrayList<MinecraftTerrainUploader.UploadedSection>();
        var committedSections = new LinkedHashMap<>(sections);
        try {
            for (Change change : latest.values()) {
                if (change instanceof Put put) {
                    var ids = committedSections.computeIfAbsent(put.sectionKey(), ignored -> new SectionIds(
                            channel.newMesh(MinecraftProgramTypes.INSTANCE_DATA), channel.newInstance()));
                    var uploaded = uploader.upload(put.mesh());
                    uploads.add(uploaded);
                    operations.add(new GeometryChannel.SetMesh<>(ids.mesh(), uploaded.build()));
                    operations.add(new GeometryChannel.SetInstance<>(ids.instance(), scene, ids.mesh(),
                            GeometryTransform.translation(put.originX(), put.originY(), put.originZ()),
                            0xff, uploaded.instanceData()));
                } else if (change instanceof Drop drop) {
                    var ids = committedSections.remove(drop.sectionKey());
                    if (ids != null) {
                        operations.add(new GeometryChannel.DropInstance(ids.instance()));
                        operations.add(new GeometryChannel.DropMesh<>(ids.mesh()));
                    }
                }
            }
            if (operations.isEmpty()) return;
            channel.submit(new RetainedBatch<>(operations, () -> retireAll(uploads)));
            sections.clear();
            sections.putAll(committedSections);
        } catch (RuntimeException | Error failure) {
            releaseRejected(uploads, failure);
            throw failure;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        if (sections.isEmpty()) {
            closed = true;
            return;
        }
        var operations = new ArrayList<GeometryChannel.Operation>(sections.size() * 2);
        for (SectionIds ids : sections.values()) {
            operations.add(new GeometryChannel.DropInstance(ids.instance()));
            operations.add(new GeometryChannel.DropMesh<>(ids.mesh()));
        }
        channel.submit(RetainedBatch.of(operations));
        sections.clear();
        closed = true;
    }

    private static void retireAll(List<? extends MinecraftTerrainUploader.UploadedSection> resources) {
        for (var resource : resources) {
            try {
                resource.close();
            } catch (Throwable ignored) {
                // Retirement callbacks must finish every release and must not escape into the engine.
            }
        }
    }

    private static void releaseRejected(List<? extends MinecraftTerrainUploader.UploadedSection> resources,
                                        Throwable rejection) {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Throwable releaseFailure) {
                rejection.addSuppressed(releaseFailure);
            }
        }
    }

    public sealed interface Change permits Put, Drop { long sectionKey(); }

    public record Put(long sectionKey, int originX, int originY, int originZ,
                      MinecraftTerrainMesh mesh) implements Change {
        public Put { Objects.requireNonNull(mesh, "mesh"); }
    }

    public record Drop(long sectionKey) implements Change { }

    private record SectionIds(MeshId<MinecraftProgramTypes.InstanceData> mesh, InstanceId instance) { }
}
