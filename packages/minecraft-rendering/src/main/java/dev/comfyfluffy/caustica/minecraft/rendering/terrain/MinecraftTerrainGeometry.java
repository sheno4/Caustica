package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.*;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainLightBatch;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
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
        var placement = new Placement(put.sectionKey(), put.originX(), put.originY(), put.originZ(), put.lights());
        var uploaded = uploader.upload(put.mesh());
        try {
            return meshes.prepare(MinecraftProgramTypes.INSTANCE_DATA, uploaded.build())
                    .handle((mesh, failure) -> {
                        if (failure != null) {
                            uploaded.close();
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                        return new Prepared(placement, uploaded, mesh);
                    });
        } catch (RuntimeException | Error failure) {
            uploaded.close();
            throw failure;
        }
    }

    /** Consumes prepared resources only after the entire direct scene edit succeeds. */
    public synchronized void edit(List<? extends ReadyChange> changes) {
        try (var edit = prepareEdit(changes)) {
            edit.publish();
        }
    }

    /**
     * Builds edits without holding the publication lock. The caller keeps prepared inputs alive until
     * publication succeeds or the edit is discarded, and excludes publication after this geometry closes.
     */
    public PreparedEdit prepareEdit(List<? extends ReadyChange> changes) {
        var base = new HashMap<Long, Section>();
        synchronized (this) {
            for (var change : changes) base.put(change.sectionKey(), sections.get(change.sectionKey()));
        }
        // Null stages removal; removing a staged key places its final replacement last.
        var next = new LinkedHashMap<Long, Section>();
        var edits = new ArrayList<SceneEdit>();
        var displaced = new ArrayList<Prepared>();
        for (var change : changes) {
            long key = change.sectionKey();
            var previous = next.containsKey(key) ? next.remove(key) : base.get(key);
            if (previous != null) {
                displaced.add(previous.prepared);
            }
            if (change instanceof Prepared prepared) {
                Placement placement = prepared.placement;
                InstanceId instance = previous == null ? channel.newInstance() : previous.instance;
                var lightIds = new ArrayList<LightId>();
                var ranges = new ArrayList<PrimitiveLightMap.Range>();
                // Descriptors identify equivalent emitters independently of mesh primitive ordering.
                // Queues preserve distinct light identities when descriptors occur more than once.
                var available = new HashMap<LightDescriptor.Parallelogram, ArrayDeque<LightId>>();
                if (previous != null) {
                    var emitters = previous.prepared.placement.lights().emitters();
                    for (int i = 0; i < emitters.size(); i++) {
                        available.computeIfAbsent(emitters.get(i).descriptor(), ignored -> new ArrayDeque<>())
                                .addLast(previous.lights.get(i));
                    }
                }
                for (var emitter : placement.lights().emitters()) {
                    var matches = available.get(emitter.descriptor());
                    var light = matches == null || matches.isEmpty() ? null : matches.removeFirst();
                    if (light == null) {
                        light = channel.newLight();
                        edits.add(new SceneEdit.SetLight(light, scene, emitter.descriptor()));
                    }
                    lightIds.add(light);
                    ranges.add(new PrimitiveLightMap.Range(emitter.firstPrimitive(), emitter.primitiveCount(), light));
                }
                available.values().forEach(lights -> lights.forEach(light -> edits.add(new SceneEdit.DropLight(light))));
                edits.add(new SceneEdit.SetInstance<>(instance, scene, prepared.mesh,
                        GeometryTransform.translation(placement.originX(), placement.originY(), placement.originZ()),
                        0xff, prepared.uploaded.instanceData(), new PrimitiveLightMap(ranges)));
                next.put(placement.sectionKey(), new Section(instance, List.copyOf(lightIds), prepared));
            } else {
                next.put(key, null);
                if (previous != null) {
                    previous.lights.forEach(light -> edits.add(new SceneEdit.DropLight(light)));
                    edits.add(new SceneEdit.DropInstance(previous.instance));
                }
            }
        }
        return new PreparedEdit(base, next, edits, displaced);
    }

    /**
     * Discarding an edit leaves all prepared inputs owned by the caller. Publication rejects changes
     * to any section used as its base; edits to unrelated sections do not invalidate it. Closing a
     * successfully published edit releases displaced resources, allowing callers to retire them
     * after leaving their publication lock.
     */
    public final class PreparedEdit implements AutoCloseable {
        private final Map<Long, Section> base;
        private final Map<Long, Section> next;
        private final List<SceneEdit> edits;
        private final List<Prepared> displaced;
        private boolean finished;
        private boolean published;

        private PreparedEdit(Map<Long, Section> base, Map<Long, Section> next,
                             List<SceneEdit> edits, List<Prepared> displaced) {
            this.base = base;
            this.next = next;
            this.edits = edits;
            this.displaced = displaced;
        }

        public void publish() {
            synchronized (MinecraftTerrainGeometry.this) {
                if (finished) throw new IllegalStateException("terrain edit is already consumed");
                for (var entry : base.entrySet()) {
                    if (sections.get(entry.getKey()) != entry.getValue()) {
                        throw new IllegalStateException("terrain section changed during edit preparation");
                    }
                }
                if (!edits.isEmpty()) channel.edit(edits);
                next.forEach((key, section) -> {
                    sections.remove(key);
                    if (section != null) sections.put(key, section);
                });
                finished = true;
                published = true;
            }
        }

        @Override public void close() {
            boolean release;
            synchronized (MinecraftTerrainGeometry.this) {
                finished = true;
                release = published;
                published = false;
            }
            if (release) {
                new ResourceLifetime(displaced.stream().<Runnable>map(value -> value::close)
                        .toArray(Runnable[]::new)).close();
            }
        }
    }

    public synchronized boolean hasSection(long key) { return sections.containsKey(key); }
    public synchronized List<Long> sectionKeys() { return List.copyOf(sections.keySet()); }

    @Override public synchronized void close() {
        var edit = prepareEdit(sectionKeys().stream().map(Drop::new).toList());
        edit.publish();
        // A rejected publication leaves sections and uploader available for retry. Once removal is
        // published, every displaced claim and the uploader must retire even if a release fails.
        new ResourceLifetime(edit::close, uploader::close).close();
    }

    public sealed interface ReadyChange permits Prepared, Drop { long sectionKey(); }

    private record Placement(long sectionKey, int originX, int originY, int originZ,
                             MinecraftTerrainLightBatch lights) { }

    public static final class Prepared implements ReadyChange, AutoCloseable {
        private final Placement placement;
        private final MinecraftTerrainUploader.UploadedSection uploaded;
        private final ReadyMesh<MinecraftProgramTypes.InstanceData> mesh;
        private final ResourceLifetime lifetime;
        private Prepared(Placement placement, MinecraftTerrainUploader.UploadedSection uploaded,
                         ReadyMesh<MinecraftProgramTypes.InstanceData> mesh) {
            this.placement = placement;
            this.uploaded = uploaded;
            this.mesh = mesh;
            this.lifetime = new ResourceLifetime(mesh::close, uploaded::close);
        }
        @Override public long sectionKey() { return placement.sectionKey(); }
        @Override public void close() { lifetime.close(); }
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
