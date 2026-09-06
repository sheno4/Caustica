package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Prepares section resources before atomically replacing neighboring instances and their lights. */
public final class MinecraftTerrainGeometry implements AutoCloseable {
    private final MeshPreparer meshes;
    private final SceneChannel channel;
    private final SceneId scene;
    private final MinecraftTerrainUploader uploader;
    private final Map<Long, Section> sections = new LinkedHashMap<>();

    public MinecraftTerrainGeometry(MeshPreparer meshes, SceneChannel channel,
                                    SceneId scene, MinecraftTerrainUploader uploader) {
        this.meshes = meshes;
        this.channel = channel;
        this.scene = scene;
        this.uploader = uploader;
    }

    public CompletableFuture<Prepared> prepare(Put put) {
        var uploaded = uploader.upload(put.mesh());
        try {
            return meshes.prepare(MinecraftProgramTypes.INSTANCE_DATA, uploaded.build())
                    .handle((mesh, failure) -> {
                        if (failure != null) {
                            uploaded.close();
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                        return new Prepared(put, uploaded, mesh);
                    });
        } catch (RuntimeException | Error failure) {
            uploaded.close();
            throw failure;
        }
    }

    /** Consumes prepared resources only after the entire direct scene edit succeeds. */
    public void edit(List<? extends ReadyChange> changes) {
        // Null stages removal; removing a staged key places its final replacement last.
        var next = new LinkedHashMap<Long, Section>();
        var edits = new ArrayList<SceneEdit>();
        var displaced = new ArrayList<Prepared>();
        for (var change : changes) {
            long key = change.sectionKey();
            var previous = next.containsKey(key) ? next.remove(key) : sections.get(key);
            if (previous != null) {
                previous.lights.forEach(light -> edits.add(new SceneEdit.DropLight(light)));
                displaced.add(previous.prepared);
            }
            if (change instanceof Prepared prepared) {
                Put put = prepared.put;
                InstanceId instance = previous == null ? channel.newInstance() : previous.instance;
                var lightIds = new ArrayList<LightId>();
                var ranges = new ArrayList<PrimitiveLightMap.Range>();
                for (var emitter : put.lights().emitters()) {
                    var light = channel.newLight();
                    lightIds.add(light);
                    edits.add(new SceneEdit.SetLight(light, scene, emitter.descriptor()));
                    ranges.add(new PrimitiveLightMap.Range(emitter.firstPrimitive(), emitter.primitiveCount(), light));
                }
                edits.add(new SceneEdit.SetInstance<>(instance, scene, prepared.mesh,
                        GeometryTransform.translation(put.originX(), put.originY(), put.originZ()),
                        0xff, prepared.uploaded.instanceData(), new PrimitiveLightMap(ranges)));
                next.put(put.sectionKey(), new Section(instance, List.copyOf(lightIds), prepared));
            } else {
                next.put(key, null);
                if (previous != null) edits.add(new SceneEdit.DropInstance(previous.instance));
            }
        }
        if (!edits.isEmpty()) channel.edit(edits);
        next.forEach((key, section) -> {
            sections.remove(key);
            if (section != null) sections.put(key, section);
        });
        displaced.forEach(Prepared::close);
    }

    public boolean hasSection(long key) { return sections.containsKey(key); }
    public List<Long> sectionKeys() { return List.copyOf(sections.keySet()); }

    @Override public void close() {
        edit(sectionKeys().stream().map(Drop::new).toList());
        uploader.close();
    }

    public sealed interface ReadyChange permits Prepared, Drop { long sectionKey(); }

    public static final class Prepared implements ReadyChange, AutoCloseable {
        private final Put put;
        private final MinecraftTerrainUploader.UploadedSection uploaded;
        private final ReadyMesh<MinecraftProgramTypes.InstanceData> mesh;
        private Prepared(Put put, MinecraftTerrainUploader.UploadedSection uploaded,
                         ReadyMesh<MinecraftProgramTypes.InstanceData> mesh) {
            this.put = put;
            this.uploaded = uploaded;
            this.mesh = mesh;
        }
        @Override public long sectionKey() { return put.sectionKey(); }
        @Override public void close() { mesh.close(); uploaded.close(); }
    }

    public record Put(long sectionKey, int originX, int originY, int originZ,
                      MinecraftTerrainMesh mesh, MinecraftTerrainLightBatch lights) {
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

    public record Drop(long sectionKey) implements ReadyChange { }


    private record Section(InstanceId instance, List<LightId> lights, Prepared prepared) { }
}
