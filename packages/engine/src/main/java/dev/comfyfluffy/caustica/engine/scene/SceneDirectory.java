package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.ReadyMesh;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Serializes atomic ready-reference edits and captures coherent snapshots. Preparation runs outside this lock. */
public final class SceneDirectory {
    private final ProgramSession programs;
    private final ResourceDirectory resources;
    private final RetainedSceneBackend backend;
    private final MeshPreparationBackend preparation;
    private final Set<SceneRef> scenes = new LinkedHashSet<>();
    private Map<InstanceRef, SceneEdit.SetInstance<?>> instances = new LinkedHashMap<>();
    private Map<LightRef, SceneEdit.SetLight> lights = new LinkedHashMap<>();
    private Map<SceneRef, LinkedHashMap<Object, EnvironmentBinding<?>>> selections = new LinkedHashMap<>();
    private ResourceOwners retained = ResourceOwners.capture(List.of());
    private long identity;
    private long revision;
    private RetainedSceneSnapshot published;

    public SceneDirectory(ProgramSession programs,
            ResourceDirectory resources,
            RetainedSceneBackend backend,
            MeshPreparationBackend preparation) {
        this.programs = Objects.requireNonNull(programs);
        this.resources = Objects.requireNonNull(resources);
        this.backend = Objects.requireNonNull(backend);
        this.preparation = Objects.requireNonNull(preparation);
    }

    public synchronized SceneId createScene() {
        SceneRef scene = new SceneRef(this, ++identity);
        scenes.add(scene);
        try {
            commit(instances, lights, selections);
        } catch (Throwable failure) {
            scenes.remove(scene);
            throw failure;
        }
        return scene;
    }

    public synchronized void dropScene(SceneId id) {
        SceneRef scene = requireScene(id);
        var nextInstances = new LinkedHashMap<>(instances);
        var nextLights = new LinkedHashMap<>(lights);
        var nextSelections = copySelections();
        nextInstances.values().removeIf(value -> value.scene() == scene);
        nextLights.values().removeIf(value -> value.scene() == scene);
        nextSelections.remove(scene);
        scenes.remove(scene);
        try {
            commit(nextInstances, nextLights, nextSelections);
        } catch (Throwable failure) {
            scenes.add(scene);
            throw failure;
        }
    }

    public synchronized SceneContributionChannel openChannel(ContributionOwner owner) {
        return new SceneContributionChannel(this, Objects.requireNonNull(owner));
    }

    public synchronized SceneEnvironmentContributionChannel openEnvironment(ContributionOwner owner, SceneId scene) {
        requireScene(scene);
        return new SceneEnvironmentContributionChannel(this, owner, scene);
    }

    synchronized InstanceId newInstance(SceneContributionChannel channel) {
        requireCreation(channel);
        return new InstanceRef(this, channel, ++identity);
    }

    synchronized LightId newLight(SceneContributionChannel channel) {
        requireCreation(channel);
        return new LightRef(this, channel, ++identity);
    }

    <N> CompletableFuture<ReadyMesh<N>> prepare(SceneContributionChannel channel,
            ShaderDataType<N> type,
            MeshBuild<N> build,
            ReadyMesh<N> source) {
        CompletableFuture<ReadyMesh<N>> result = new CompletableFuture<>();
        CompletableFuture<Void> terminal = new CompletableFuture<>();
        ResourceOwners inputs;
        ReadyMesh<N> sourceClaim;
        RetainedSceneSnapshot.Mesh mesh;
        synchronized (this) {
            requireCreation(channel);
            Objects.requireNonNull(type);
            Objects.requireNonNull(build);
            validateBuild(channel.owner, type, build);
            if (source != null) {
                requireReady(source);
                if (source.instanceDataType() != type) {
                    throw new IllegalArgumentException("refit schema mismatch");
                }
            }
            inputs = ResourceOwners.capture(buildReferences(build));
            try {
                sourceClaim = source == null ? null : source.retain();
            } catch (Throwable error) {
                inputs.close();
                throw error;
            }
            mesh = meshSnapshot(++identity, build, null);
            channel.preparations.add(terminal);
        }
        try {
            preparation.prepare(mesh,
                    sourceClaim == null ? null : preparedResource(sourceClaim)).whenComplete((nativeOwner, error) -> {
                try {
                    if (error != null) {
                        inputs.close();
                        result.completeExceptionally(error);
                    } else {
                        ReadyState<N> state = new ReadyState<>(this, mesh, type, nativeOwner, inputs);
                        ReadyClaim<N> claim = new ReadyClaim<>(state);
                        claim.producer = channel;
                        synchronized (this) {
                            if (channel.acceptingEdits && !result.isCancelled()) {
                                channel.meshes.add(claim);
                            } else {
                                claim.close();
                                result.completeExceptionally(new IllegalStateException("mesh scope closed"));
                                return;
                            }
                        }
                        if (!result.complete(claim)) {
                            claim.close();
                        }
                    }
                } finally {
                    if (sourceClaim != null) {
                        sourceClaim.close();
                    }
                    synchronized (this) {
                        channel.preparations.remove(terminal);
                    }
                    terminal.complete(null);
                }
            });
        } catch (Throwable error) {
            inputs.close();
            if (sourceClaim != null) {
                sourceClaim.close();
            }
            synchronized (this) {
                channel.preparations.remove(terminal);
            }
            terminal.complete(null);
            result.completeExceptionally(error);
        }
        return result;
    }

    /** Borrowed native preparation result; the caller must retain the ready mesh throughout use. */
    public static ResourceOwner preparedResource(ReadyMesh<?> ready) {
        return ((ReadyClaim<?>) ready).state.nativeOwner;
    }

    synchronized void edit(SceneContributionChannel channel, List<? extends SceneEdit> edits) {
        requireEdits(channel);
        var nextInstances = new LinkedHashMap<>(instances);
        var nextLights = new LinkedHashMap<>(lights);
        var nextSelections = copySelections();
        for (SceneEdit edit : List.copyOf(edits)) {
            if (edit instanceof SceneEdit.SetInstance<?> set) {
                InstanceRef id = requireInstance(channel, set.instance());
                validateInstance(channel, set);
                nextInstances.put(id, set);
            } else if (edit instanceof SceneEdit.DropInstance drop) {
                nextInstances.remove(requireInstance(channel, drop.instance()));
            } else if (edit instanceof SceneEdit.SetTransform set) {
                InstanceRef id = requireInstance(channel, set.instance());
                var current = nextInstances.get(id);
                if (current == null) {
                    throw new IllegalArgumentException("transform names absent instance");
                }
                nextInstances.put(id, withTransform(current, set));
            } else if (edit instanceof SceneEdit.SetLight set) {
                LightRef id = requireLight(channel, set.light());
                requireScene(set.scene());
                nextLights.put(id, set);
            } else if (edit instanceof SceneEdit.DropLight drop) {
                nextLights.remove(requireLight(channel, drop.light()));
            } else if (edit instanceof SceneEdit.SetEnvironment set) {
                select(nextSelections, channel, channel.owner, set.scene(), set.binding());
            }
        }
        commit(nextInstances, nextLights, nextSelections);
    }

    private void validateInstance(SceneContributionChannel channel, SceneEdit.SetInstance<?> instance) {
        requireScene(instance.scene());
        ReadyState<?> mesh = requireReady(instance.mesh());
        mesh.type.require(instance.instanceData());
        resources.validate(channel.owner, instance.instanceData().resource());
        int triangleCount = mesh.mesh.build().geometries().stream()
                .mapToInt(geometry -> Math.addExact(geometry.firstIndex(), geometry.indexCount()) / 3)
                .max().orElseThrow();
        for (var range : instance.primitiveLights().ranges()) {
            requireLightSelection(range.light());
            if (Math.addExact(range.firstPrimitive(), range.primitiveCount()) > triangleCount) {
                throw new IllegalArgumentException("primitive-light range exceeds mesh");
            }
        }
    }

    private static <N> SceneEdit.SetInstance<N> withTransform(SceneEdit.SetInstance<N> current,
            SceneEdit.SetTransform set) {
        return new SceneEdit.SetInstance<>(current.instance(),
                current.scene(),
                current.mesh(),
                set.transform(),
                set.mask(),
                current.instanceData(),
                current.primitiveLights());
    }

    synchronized void selectEnvironment(SceneEnvironmentContributionChannel channel, EnvironmentBinding<?> binding) {
        if (!channel.accepting) {
            throw new IllegalStateException("environment scope closed");
        }
        var nextSelections = copySelections();
        select(nextSelections, channel, channel.owner, channel.scene, binding);
        commit(instances, lights, nextSelections);
    }

    private void select(Map<SceneRef, LinkedHashMap<Object, EnvironmentBinding<?>>> next,
            Object slot,
            ContributionOwner owner,
            SceneId id,
            EnvironmentBinding<?> binding) {
        SceneRef scene = requireScene(id);
        programs.validateEnvironment(binding.implementation(), binding.bindingData());
        resources.validate(owner, binding.bindingData().resource());
        var values = next.computeIfAbsent(scene, key -> new LinkedHashMap<>());
        values.remove(slot);
        values.put(slot, binding);
    }

    synchronized void quiesce(SceneContributionChannel channel) {
        channel.acceptingIdentities = false;
    }

    synchronized void invalidate(SceneContributionChannel channel) {
        if (!channel.acceptingEdits) {
            return;
        }
        quiesce(channel);
        var nextInstances = new LinkedHashMap<>(instances);
        nextInstances.keySet().removeIf(id -> id.owner == channel);
        var nextLights = new LinkedHashMap<>(lights);
        nextLights.keySet().removeIf(id -> id.owner == channel);
        var nextSelections = copySelections();
        nextSelections.values().forEach(values -> values.remove(channel));
        commit(nextInstances, nextLights, nextSelections);
        channel.acceptingEdits = false;
        var claims = List.copyOf(channel.meshes);
        channel.meshes.clear();
        claims.forEach(ReadyMesh::close);
    }

    synchronized void invalidate(SceneEnvironmentContributionChannel channel) {
        if (!channel.accepting) {
            return;
        }
        var nextSelections = copySelections();
        nextSelections.values().forEach(values -> values.remove(channel));
        commit(instances, lights, nextSelections);
        channel.accepting = false;
    }

    public void drain(SceneContributionChannel channel) {
        backend.settleFrameUses();
        CompletableFuture<?>[] pending;
        synchronized (this) {
            pending = channel.preparations.toArray(CompletableFuture[]::new);
        }
        CompletableFuture.allOf(pending).join();
    }

    public void drain(SceneEnvironmentContributionChannel channel) {
        backend.settleFrameUses();
    }

    public void progress() {
        synchronized (this) {
            RetainedSceneSnapshot next = snapshot(revision + 1, instances, lights, selections, retained);
            if (published != null && !next.meshes().equals(published.meshes())) {
                backend.apply(next);
                published = next;
                revision++;
            }
        }
        backend.progress();
    }

    public void settleFrameUses() {
        backend.settleFrameUses();
    }

    public void prepareForSessionClose() {
        backend.prepareForSessionClose();
    }

    /** Diagnostic values with borrowed resource claims; rendering captures the owning backend root. */
    public synchronized RetainedSceneSnapshot snapshot() {
        return snapshot(revision, instances, lights, selections, retained);
    }

    private Map<SceneRef, LinkedHashMap<Object, EnvironmentBinding<?>>> copySelections() {
        var copy = new LinkedHashMap<SceneRef, LinkedHashMap<Object, EnvironmentBinding<?>>>();
        selections.forEach((key, value) -> copy.put(key, new LinkedHashMap<>(value)));
        return copy;
    }

    private void commit(Map<InstanceRef, SceneEdit.SetInstance<?>> nextInstances,
            Map<LightRef, SceneEdit.SetLight> nextLights,
            Map<SceneRef, LinkedHashMap<Object, EnvironmentBinding<?>>> nextSelections) {
        List<ResourceRef> references = new ArrayList<>();
        nextInstances.values().forEach(value -> {
            references.add(value.mesh().reference());
            references.add(value.instanceData().resource());
        });
        nextSelections.values().forEach(values -> values.values().forEach(
                binding -> references.add(binding.bindingData().resource())));
        ResourceOwners nextResources = ResourceOwners.capture(references);
        RetainedSceneSnapshot candidate;
        try {
            candidate = snapshot(revision + 1, nextInstances, nextLights, nextSelections, nextResources);
            backend.apply(candidate);
        } catch (Throwable error) {
            nextResources.close();
            throw error;
        }
        ResourceOwners previousResources = retained;
        retained = nextResources;
        instances = nextInstances;
        lights = nextLights;
        selections = nextSelections;
        published = candidate;
        revision++;
        previousResources.close();
    }

    private RetainedSceneSnapshot snapshot(
            long snapshotRevision,
            Map<InstanceRef, SceneEdit.SetInstance<?>> nextInstances,
            Map<LightRef, SceneEdit.SetLight> nextLights,
            Map<SceneRef, LinkedHashMap<Object, EnvironmentBinding<?>>> nextSelections,
            ResourceOwners owners) {
        var meshSnapshots = new LinkedHashMap<Long, RetainedSceneSnapshot.Mesh>();
        for (var instance : nextInstances.values()) {
            var mesh = ((ReadyClaim<?>) instance.mesh()).state;
            var ready = (ReadyMesh<?>) owners.borrowed(instance.mesh().reference());
            meshSnapshots.putIfAbsent(mesh.mesh.identity(),
                    meshSnapshot(mesh.mesh.identity(), mesh.mesh.build(), ready));
        }
        List<RetainedSceneSnapshot.Scene> sceneSnapshots = scenes.stream()
                .map(scene -> new RetainedSceneSnapshot.Scene(scene, selectedEnvironment(nextSelections.get(scene))))
                .toList();
        List<RetainedSceneSnapshot.Instance> instanceSnapshots = nextInstances.entrySet().stream()
                .map(entry -> instanceSnapshot(entry.getKey(), entry.getValue())).toList();
        List<RetainedSceneSnapshot.Light> lightSnapshots = nextLights.entrySet().stream()
                .map(entry -> new RetainedSceneSnapshot.Light(entry.getKey().identity,
                        entry.getValue().scene(), entry.getValue().descriptor())).toList();
        return new RetainedSceneSnapshot(snapshotRevision,
                sceneSnapshots,
                List.copyOf(meshSnapshots.values()),
                instanceSnapshots,
                lightSnapshots);
    }

    private static EnvironmentBinding<?> selectedEnvironment(LinkedHashMap<Object, EnvironmentBinding<?>> selections) {
        return selections == null || selections.isEmpty() ? null : selections.lastEntry().getValue();
    }

    private static RetainedSceneSnapshot.Instance instanceSnapshot(InstanceRef id, SceneEdit.SetInstance<?> instance) {
        var primitiveEmitters = instance.primitiveLights().ranges().stream()
                .map(range -> new RetainedSceneSnapshot.PrimitiveEmitter(range.firstPrimitive(),
                        range.primitiveCount(), ((LightRef) range.light()).identity)).toList();
        return new RetainedSceneSnapshot.Instance(id.identity,
                instance.scene(),
                ((ReadyClaim<?>) instance.mesh()).state.mesh.identity(),
                instance.transform(),
                instance.mask(),
                instance.instanceData(),
                primitiveEmitters);
    }

    private RetainedSceneSnapshot.Mesh meshSnapshot(long id, MeshBuild<?> build, ReadyMesh<?> ready) {
        var geometryPrograms = build.geometries().stream().map(geometry -> new RetainedSceneSnapshot.GeometryPrograms(
                geometry.surface() == null ? 0 : programs.resolve(geometry.surface().surface()),
                geometry.volume() == null ? 0 : programs.resolve(geometry.volume().volume()))).toList();
        return new RetainedSceneSnapshot.Mesh(id, build, geometryPrograms, ready);
    }

    private void validateBuild(ContributionOwner owner, ShaderDataType<?> type, MeshBuild<?> build) {
        buildReferences(build).forEach(ref -> resources.validate(owner, ref));
        for (var geometry : build.geometries()) {
            if (geometry.surface() != null) {
                programs.validateSurface(geometry.surface().surface(),
                        geometry.surface().bindingData(),
                        type,
                        !(geometry.surface().coverage() instanceof MeshBuild.CoveragePolicy.Opaque));
            }
            if (geometry.volume() != null) {
                programs.validateVolume(geometry.volume().volume(), geometry.volume().bindingData(), type);
            }
        }
    }

    private static List<ResourceRef> buildReferences(MeshBuild<?> build) {
        var references = new ArrayList<ResourceRef>();
        references.add(build.positions().resource());
        references.add(build.indices().resource());
        for (var geometry : build.geometries()) {
            if (geometry.surface() != null) {
                references.add(geometry.surface().bindingData().resource());
            }
            if (geometry.volume() != null) {
                references.add(geometry.volume().bindingData().resource());
            }
        }
        return references;
    }

    private SceneRef requireScene(SceneId id) {
        if (!(id instanceof SceneRef scene) || scene.directory != this || !scenes.contains(scene)) {
            throw new IllegalArgumentException("scene is stale or foreign");
        }
        return scene;
    }

    private ReadyState<?> requireReady(ReadyMesh<?> mesh) {
        if (!(mesh instanceof ReadyClaim<?> claim) || claim.state.directory != this) {
            throw new IllegalArgumentException("mesh belongs to another session");
        }
        synchronized (claim.state) {
            if (claim.closed) {
                throw new IllegalStateException("mesh claim closed");
            }
        }
        return claim.state;
    }

    private InstanceRef requireInstance(SceneContributionChannel owner, InstanceId id) {
        if (!(id instanceof InstanceRef ref) || ref.directory != this || ref.owner != owner) {
            throw new IllegalArgumentException("foreign instance mutation");
        }
        return ref;
    }

    private LightRef requireLight(SceneContributionChannel owner, LightId id) {
        LightRef ref = requireLightSelection(id);
        if (ref.owner != owner) {
            throw new IllegalArgumentException("foreign light mutation");
        }
        return ref;
    }

    private LightRef requireLightSelection(LightId id) {
        if (!(id instanceof LightRef ref) || ref.directory != this) {
            throw new IllegalArgumentException("foreign light selection");
        }
        return ref;
    }

    private void requireCreation(SceneContributionChannel channel) {
        if (channel.directory != this || !channel.acceptingIdentities) {
            throw new IllegalStateException("scene scope quiesced");
        }
    }

    private void requireEdits(SceneContributionChannel channel) {
        if (channel.directory != this || !channel.acceptingEdits) {
            throw new IllegalStateException("scene scope closed");
        }
    }

    private record SceneRef(SceneDirectory directory, long identity) implements SceneId {
    }

    private record InstanceRef(SceneDirectory directory,
            SceneContributionChannel owner,
            long identity) implements InstanceId {
    }

    private record LightRef(SceneDirectory directory,
            SceneContributionChannel owner,
            long identity) implements LightId {
    }

    private static final class ReadyState<N> implements ResourceRef {
        final SceneDirectory directory;
        final RetainedSceneSnapshot.Mesh mesh;
        final ShaderDataType<N> type;
        final ResourceOwner nativeOwner;
        final ResourceOwners inputs;
        int references = 1;

        ReadyState(SceneDirectory directory,
                RetainedSceneSnapshot.Mesh mesh,
                ShaderDataType<N> type,
                ResourceOwner nativeOwner,
                ResourceOwners inputs) {
            this.directory = directory;
            this.mesh = mesh;
            this.type = type;
            this.nativeOwner = nativeOwner;
            this.inputs = inputs;
        }

        @Override
        public synchronized ReadyMesh<N> retain() {
            if (references == 0) {
                throw new IllegalStateException("mesh released");
            }
            references++;
            return new ReadyClaim<>(this);
        }
    }

    private static final class ReadyClaim<N> implements ReadyMesh<N> {
        final ReadyState<N> state;
        boolean closed;
        SceneContributionChannel producer;

        ReadyClaim(ReadyState<N> state) {
            this.state = state;
        }

        @Override
        public ShaderDataType<N> instanceDataType() {
            return state.type;
        }

        @Override
        public ResourceRef reference() {
            return state;
        }

        @Override
        public ReadyMesh<N> retain() {
            synchronized (state) {
                if (closed) {
                    throw new IllegalStateException("mesh claim closed");
                }
                return state.retain();
            }
        }

        @Override
        public void close() {
            if (producer != null) {
                synchronized (state.directory) {
                    producer.meshes.remove(this);
                    release();
                }
            } else {
                release();
            }
        }

        private void release() {
            synchronized (state) {
                if (closed) {
                    return;
                }
                closed = true;
                if (--state.references == 0) {
                    state.nativeOwner.close();
                    state.inputs.close();
                }
            }
        }
    }
}
