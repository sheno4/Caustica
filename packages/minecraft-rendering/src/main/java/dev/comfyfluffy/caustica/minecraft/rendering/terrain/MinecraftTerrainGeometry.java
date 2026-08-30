package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.GeometryPublication;
import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Retained geometry and matching finite-light owner for the sections of one borrowed Minecraft scene. */
public final class MinecraftTerrainGeometry implements AutoCloseable {
    private final GeometryChannel channel;
    private final LightChannel lights;
    private final SceneId scene;
    private final MinecraftTerrainUploader uploader;
    private final Map<Long, SectionIds> sections = new LinkedHashMap<>();
    private boolean closed;

    public MinecraftTerrainGeometry(GeometryChannel channel, LightChannel lights,
                                    SceneId scene, MinecraftTerrainUploader uploader) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
    }

    /** Atomically replaces and removes all sections named by one Minecraft extraction transaction. */
    public synchronized GeometryPublication submit(List<Change> changes) {
        return submitGroup(List.of(changes));
    }

    /** Publishes extraction transactions together while retaining each transaction independently. */
    public synchronized GeometryPublication submitGroup(List<? extends List<Change>> groups) {
        if (closed) throw new IllegalStateException("terrain geometry is closed");
        if (groups.isEmpty()) return GeometryPublication.alreadyVisible();
        var batches = new ArrayList<RetainedBatch<GeometryChannel.Operation>>();
        var preparedUploads = new ArrayList<MinecraftTerrainUploader.UploadedSection>();
        var lightOperations = new ArrayList<LightChannel.Operation>();
        var committedSections = new LinkedHashMap<>(sections);
        GeometryPublication publication;
        try {
            for (List<Change> changes : groups) {
                var batch = prepareBatch(changes, committedSections, preparedUploads);
                if (batch.operations().isEmpty()) continue;
                batches.add(new RetainedBatch<>(batch.operations(),
                        () -> retireAll(batch.uploads())));
                lightOperations.addAll(batch.lightOperations());
            }
            if (batches.isEmpty()) return GeometryPublication.alreadyVisible();
            publication = lightOperations.isEmpty()
                    ? channel.submitGroup(batches)
                    : channel.submitWithLights(batches, lights, RetainedBatch.of(lightOperations));
        } catch (RuntimeException | Error failure) {
            releaseRejected(preparedUploads, failure);
            throw failure;
        }
        sections.clear();
        sections.putAll(committedSections);
        return publication;
    }

    /** Whether an accepted retained-scene transaction currently owns geometry for this section. */
    public synchronized boolean hasSection(long sectionKey) {
        return sections.containsKey(sectionKey);
    }

    /** Snapshot of section keys owned by accepted retained-scene transactions. */
    public synchronized List<Long> sectionKeys() {
        return List.copyOf(sections.keySet());
    }

    private PreparedBatch prepareBatch(List<Change> changes, Map<Long, SectionIds> committedSections,
                                       List<MinecraftTerrainUploader.UploadedSection> preparedUploads) {
        var latest = new LinkedHashMap<Long, Change>();
        for (Change change : changes) latest.put(change.sectionKey(), change);
        var operations = new ArrayList<GeometryChannel.Operation>();
        var uploads = new ArrayList<MinecraftTerrainUploader.UploadedSection>();
        var lightOperations = new ArrayList<LightChannel.Operation>();
        for (Change change : latest.values()) {
            if (change instanceof Put put) {
                var previous = committedSections.get(put.sectionKey());
                var mesh = previous == null
                        ? channel.newMesh(MinecraftProgramTypes.INSTANCE_DATA) : previous.mesh();
                var instance = previous == null ? channel.newInstance() : previous.instance();
                var uploaded = uploader.upload(put.mesh());
                preparedUploads.add(uploaded);
                operations.add(new GeometryChannel.SetMesh<>(mesh, uploaded.build()));
                var lightIds = new ArrayList<LightId>(put.lights().emitters().size());
                var lightRanges = new ArrayList<PrimitiveLightMap.Range>(put.lights().emitters().size());
                if (previous != null) {
                    previous.lights().forEach(light -> lightOperations.add(new LightChannel.DropLight(light)));
                }
                for (var emitter : put.lights().emitters()) {
                    LightId light = lights.newLight();
                    lightIds.add(light);
                    lightOperations.add(new LightChannel.SetLight(light, scene, emitter.descriptor()));
                    lightRanges.add(new PrimitiveLightMap.Range(
                            emitter.firstPrimitive(), emitter.primitiveCount(), light));
                }
                operations.add(new GeometryChannel.SetInstance<>(instance, scene, mesh,
                        GeometryTransform.translation(put.originX(), put.originY(), put.originZ()),
                        0xff, uploaded.instanceData(), new PrimitiveLightMap(lightRanges)));
                committedSections.put(put.sectionKey(),
                        new SectionIds(mesh, instance, List.copyOf(lightIds)));
                uploads.add(uploaded);
            } else if (change instanceof Drop drop) {
                var ids = committedSections.remove(drop.sectionKey());
                if (ids != null) {
                    operations.add(new GeometryChannel.DropInstance(ids.instance()));
                    operations.add(new GeometryChannel.DropMesh<>(ids.mesh()));
                    ids.lights().forEach(light -> lightOperations.add(new LightChannel.DropLight(light)));
                }
            }
        }
        return new PreparedBatch(List.copyOf(operations), List.copyOf(uploads),
                List.copyOf(lightOperations));
    }

    @Override public synchronized void close() {
        if (closed) return;
        if (sections.isEmpty()) {
            closed = true;
            uploader.close();
            return;
        }
        var operations = new ArrayList<GeometryChannel.Operation>(sections.size() * 2);
        var lightOperations = new ArrayList<LightChannel.Operation>();
        for (SectionIds ids : sections.values()) {
            operations.add(new GeometryChannel.DropInstance(ids.instance()));
            operations.add(new GeometryChannel.DropMesh<>(ids.mesh()));
            ids.lights().forEach(light -> lightOperations.add(new LightChannel.DropLight(light)));
        }
        if (lightOperations.isEmpty()) channel.submit(RetainedBatch.of(operations));
        else channel.submitWithLights(List.of(RetainedBatch.of(operations)), lights,
                RetainedBatch.of(lightOperations));
        sections.clear();
        closed = true;
        uploader.close();
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
                      MinecraftTerrainMesh mesh, MinecraftTerrainLightBatch lights) implements Change {
        public Put(long sectionKey, int originX, int originY, int originZ, MinecraftTerrainMesh mesh) {
            this(sectionKey, originX, originY, originZ, mesh,
                    new MinecraftTerrainLightBatch(sectionKey, 0L, List.of()));
        }

        public Put {
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(lights, "lights");
            if (lights.sectionKey() != sectionKey) {
                throw new IllegalArgumentException("terrain light batch must name the same section");
            }
        }
    }

    public record Drop(long sectionKey) implements Change { }

    private record PreparedBatch(List<GeometryChannel.Operation> operations,
                                 List<MinecraftTerrainUploader.UploadedSection> uploads,
                                 List<LightChannel.Operation> lightOperations) { }

    private record SectionIds(MeshId<MinecraftProgramTypes.InstanceData> mesh, InstanceId instance,
                              List<LightId> lights) { }
}
