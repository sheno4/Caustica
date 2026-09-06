package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.ReadyMesh;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.support.SharedResource;

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
    private final Map<InstanceRef, RetainedInstance> instances = new LinkedHashMap<>();
    private final Map<LightRef, RetainedSceneSnapshot.Light> lights = new LinkedHashMap<>();
    private final Map<SceneRef, LinkedHashMap<Object, RetainedEnvironment>> selections = new LinkedHashMap<>();
    private long identity;
    private long revision;

    public SceneDirectory(ProgramSession programs,
            ResourceDirectory resources,
            RetainedSceneBackend backend,
            MeshPreparationBackend preparation) {
        this.programs = Objects.requireNonNull(programs);
        this.resources = Objects.requireNonNull(resources);
        this.backend = Objects.requireNonNull(backend);
        this.preparation = Objects.requireNonNull(preparation);
        backend.bind(this::capture);
    }

    public synchronized SceneId createScene() {
        SceneRef scene = new SceneRef(this, ++identity);
        scenes.add(scene);
        revision++;
        return scene;
    }

    public synchronized void dropScene(SceneId id) {
        SceneRef scene = requireScene(id);
        removeInstances(entry -> entry.getValue().value.scene() == scene);
        lights.values().removeIf(value -> value.scene() == scene);
        var removed = selections.remove(scene);
        if (removed != null) removed.values().forEach(RetainedEnvironment::close);
        scenes.remove(scene);
        revision++;
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
            inputs = new ResourceOwners();
            try {
                sourceClaim = source == null ? null : source.retain();
            } catch (Throwable error) {
                inputs.close();
                throw error;
            }
            try { mesh = meshSnapshot(++identity, inputs.mesh(build), null); }
            catch (Throwable failure) {
                inputs.close();
                if (sourceClaim != null) sourceClaim.close();
                throw failure;
            }
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
        var changedInstances = new LinkedHashMap<InstanceRef, RetainedInstance>();
        var changedLights = new LinkedHashMap<LightRef, SceneEdit.SetLight>();
        var changedEnvironments = new LinkedHashMap<SceneRef, EnvironmentBinding<?>>();
        for (SceneEdit edit : List.copyOf(edits)) {
            if (edit instanceof SceneEdit.SetInstance<?> set) {
                InstanceRef id = requireInstance(channel, set.instance());
                validateInstance(channel, set);
                RetainedInstance current = changedInstances.containsKey(id) ? changedInstances.get(id) : instances.get(id);
                changedInstances.put(id, new RetainedInstance(set,
                        current == null ? ++identity : current.placementOrdinal, null, primitiveEmitters(set)));
            } else if (edit instanceof SceneEdit.DropInstance drop) {
                changedInstances.put(requireInstance(channel, drop.instance()), null);
            } else if (edit instanceof SceneEdit.SetTransform set) {
                InstanceRef id = requireInstance(channel, set.instance());
                RetainedInstance current = changedInstances.containsKey(id) ? changedInstances.get(id) : instances.get(id);
                if (current == null) throw new IllegalArgumentException("transform names absent instance");
                changedInstances.put(id, new RetainedInstance(withTransform(current.value, set),
                        current.placementOrdinal, current.resources, current.primitiveEmitters));
            } else if (edit instanceof SceneEdit.SetLight set) {
                LightRef id = requireLight(channel, set.light());
                requireScene(set.scene());
                changedLights.put(id, set);
            } else if (edit instanceof SceneEdit.DropLight drop) {
                changedLights.put(requireLight(channel, drop.light()), null);
            } else if (edit instanceof SceneEdit.SetEnvironment set) {
                SceneRef scene = validateEnvironment(channel.owner, set.scene(), set.binding());
                changedEnvironments.put(scene, set.binding());
            }
        }
        // Acquire only replacement entries before changing the database so a failed claim leaves the group intact.
        var acquired = new ArrayList<AutoCloseable>();
        var environments = new LinkedHashMap<SceneRef, RetainedEnvironment>();
        try {
            changedInstances.replaceAll((id, entry) -> {
                if (entry == null || entry.resources != null) return entry;
                ResourceOwners owners = ResourceOwners.capture(List.of(
                        entry.value.mesh(), entry.value.instanceData()));
                acquired.add(owners);
                return new RetainedInstance(ownInstance(entry.value, owners), entry.placementOrdinal,
                        SharedResource.owned(owners, ResourceOwners::close), entry.primitiveEmitters);
            });
            changedEnvironments.forEach((scene, binding) -> {
                RetainedEnvironment entry = ownEnvironment(binding);
                acquired.add(entry);
                environments.put(scene, entry);
            });
        } catch (Throwable failure) {
            acquired.forEach(value -> {
                try { value.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            });
            throw failure;
        }
        var retired = new ArrayList<SharedResource<ResourceOwners>>();
        changedInstances.forEach((id, entry) -> {
            RetainedInstance previous = entry == null ? instances.remove(id) : instances.put(id, entry);
            if (previous != null && (entry == null || previous.resources != entry.resources)) retired.add(previous.resources);
        });
        changedLights.forEach((id, value) -> {
            if (value == null) lights.remove(id);
            else lights.put(id, new RetainedSceneSnapshot.Light(id.identity, value.scene(), value.descriptor()));
        });
        environments.forEach((scene, entry) -> replaceEnvironment(scene, channel, entry));
        revision++;
        retired.forEach(SharedResource::close);
    }

    private static RetainedEnvironment ownEnvironment(EnvironmentBinding<?> binding) {
        var owners = new ResourceOwners();
        try { return new RetainedEnvironment(owners.environment(binding), SharedResource.owned(owners, ResourceOwners::close)); }
        catch (Throwable failure) { owners.close(); throw failure; }
    }

    private static <N> SceneEdit.SetInstance<N> ownInstance(SceneEdit.SetInstance<N> value, ResourceOwners owners) {
        @SuppressWarnings("unchecked")
        ReadyMesh<N> mesh = (ReadyMesh<N>) owners.borrowed(value.mesh());
        return new SceneEdit.SetInstance<>(value.instance(), value.scene(), mesh, value.transform(), value.mask(),
                owners.data(value.instanceData()), value.primitiveLights());
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
        SceneRef scene = validateEnvironment(channel.owner, channel.scene, binding);
        replaceEnvironment(scene, channel,
                ownEnvironment(binding));
        revision++;
    }

    private SceneRef validateEnvironment(ContributionOwner owner, SceneId id, EnvironmentBinding<?> binding) {
        SceneRef scene = requireScene(id);
        programs.validateEnvironment(binding.implementation(), binding.bindingData());
        resources.validate(owner, binding.bindingData().resource());
        return scene;
    }

    private void replaceEnvironment(SceneRef scene, Object slot, RetainedEnvironment entry) {
        var values = selections.computeIfAbsent(scene, key -> new LinkedHashMap<>());
        RetainedEnvironment previous = values.remove(slot);
        values.put(slot, entry);
        if (previous != null) previous.close();
    }

    private void removeInstances(java.util.function.Predicate<Map.Entry<InstanceRef, RetainedInstance>> predicate) {
        instances.entrySet().removeIf(entry -> {
            if (!predicate.test(entry)) return false;
            entry.getValue().resources.close();
            return true;
        });
    }

    private void removeEnvironments(Object slot) {
        selections.values().forEach(values -> {
            RetainedEnvironment previous = values.remove(slot);
            if (previous != null) previous.close();
        });
    }

    synchronized void quiesce(SceneContributionChannel channel) {
        channel.acceptingIdentities = false;
    }

    synchronized void invalidate(SceneContributionChannel channel) {
        if (!channel.acceptingEdits) return;
        quiesce(channel);
        removeInstances(entry -> entry.getKey().owner == channel);
        lights.keySet().removeIf(id -> id.owner == channel);
        removeEnvironments(channel);
        revision++;
        channel.acceptingEdits = false;
        var claims = List.copyOf(channel.meshes);
        channel.meshes.clear();
        claims.forEach(ReadyMesh::close);
    }

    synchronized void invalidate(SceneEnvironmentContributionChannel channel) {
        if (!channel.accepting) return;
        removeEnvironments(channel);
        revision++;
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

    public void progress() { backend.progress(); }

    public void settleFrameUses() { backend.settleFrameUses(); }

    public void prepareForSessionClose() { backend.prepareForSessionClose(); }

    /** Borrowed diagnostic values; callers needing stable resource lifetime use capture(). */
    public synchronized RetainedSceneSnapshot snapshot() {
        synchronized (programs) {
            return snapshotValues();
        }
    }

    /** Captures all scenes while retaining their immutable dependency groups at one atomic edit boundary. */
    public synchronized SharedResource<RetainedSceneSnapshot> capture() {
        var owners = new ArrayList<SharedResource<ResourceOwners>>(instances.size() + scenes.size());
        try {
            instances.values().forEach(entry -> owners.add(entry.resources.retain()));
            selections.values().forEach(values -> {
                if (!values.isEmpty()) owners.add(values.lastEntry().getValue().owner.retain());
            });
            synchronized (programs) {
                return SharedResource.owned(snapshotValues(), ignored -> owners.forEach(SharedResource::close));
            }
        } catch (Throwable failure) {
            owners.forEach(SharedResource::close);
            throw failure;
        }
    }

    private RetainedSceneSnapshot snapshotValues() {
        long programRevision = programs.resolutionRevision();
        var meshSnapshots = new LinkedHashMap<Long, RetainedSceneSnapshot.Mesh>();
        for (var entry : instances.values()) {
            var ready = (ReadyClaim<?>) entry.value.mesh();
            long identity = ready.state.mesh.identity();
            if (!meshSnapshots.containsKey(identity)) {
                meshSnapshots.put(identity, ready.snapshot(programRevision));
            }
        }
        var sceneSnapshots = new ArrayList<RetainedSceneSnapshot.Scene>(scenes.size());
        for (var scene : scenes) {
            sceneSnapshots.add(new RetainedSceneSnapshot.Scene(scene, selectedEnvironment(selections.get(scene))));
        }
        var instanceSnapshots = new ArrayList<RetainedSceneSnapshot.Instance>(instances.size());
        for (var entry : instances.values()) instanceSnapshots.add(entry.snapshot);
        return new RetainedSceneSnapshot(revision, sceneSnapshots, List.copyOf(meshSnapshots.values()),
                instanceSnapshots, List.copyOf(lights.values()));
    }

    private static EnvironmentBinding<?> selectedEnvironment(LinkedHashMap<Object, RetainedEnvironment> selections) {
        return selections == null || selections.isEmpty() ? null : selections.lastEntry().getValue().binding;
    }

    private static final class RetainedInstance {
        final SceneEdit.SetInstance<?> value;
        final long placementOrdinal;
        final SharedResource<ResourceOwners> resources;
        final List<RetainedSceneSnapshot.PrimitiveEmitter> primitiveEmitters;
        final RetainedSceneSnapshot.Instance snapshot;

        RetainedInstance(SceneEdit.SetInstance<?> value, long placementOrdinal,
                         SharedResource<ResourceOwners> resources,
                         List<RetainedSceneSnapshot.PrimitiveEmitter> primitiveEmitters) {
            this.value = value;
            this.placementOrdinal = placementOrdinal;
            this.resources = resources;
            this.primitiveEmitters = primitiveEmitters;
            snapshot = new RetainedSceneSnapshot.Instance(((InstanceRef) value.instance()).identity, placementOrdinal,
                    value.scene(), ((ReadyClaim<?>) value.mesh()).state.mesh.identity(), value.transform(),
                    value.mask(), value.instanceData(), primitiveEmitters);
        }
    }
    private record RetainedEnvironment(EnvironmentBinding<?> binding, SharedResource<ResourceOwners> owner) implements AutoCloseable {
        @Override public void close() { owner.close(); }
    }

    private static List<RetainedSceneSnapshot.PrimitiveEmitter> primitiveEmitters(SceneEdit.SetInstance<?> instance) {
        return instance.primitiveLights().ranges().stream()
                .map(range -> new RetainedSceneSnapshot.PrimitiveEmitter(range.firstPrimitive(),
                        range.primitiveCount(), ((LightRef) range.light()).identity)).toList();
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

    private static List<ResourceOwner> buildReferences(MeshBuild<?> build) {
        var references = new ArrayList<ResourceOwner>();
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
        return claim.owner.get();
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

    private static final class ReadyState<N> {
        final SceneDirectory directory;
        final RetainedSceneSnapshot.Mesh mesh;
        final ShaderDataType<N> type;
        final ResourceOwner nativeOwner;
        final SharedResource<ReadyState<N>> initial;

        ReadyState(SceneDirectory directory,
                RetainedSceneSnapshot.Mesh mesh,
                ShaderDataType<N> type,
                ResourceOwner nativeOwner,
                ResourceOwners inputs) {
            this.directory = directory;
            this.mesh = mesh;
            this.type = type;
            this.nativeOwner = nativeOwner;
            initial = SharedResource.owned(this, ignored -> {
                try { nativeOwner.close(); } finally { inputs.close(); }
            });
        }

    }

    private static final class ReadyClaim<N> implements ReadyMesh<N> {
        final ReadyState<N> state;
        SceneContributionChannel producer;

        final SharedResource<ReadyState<N>> owner;
        private long snapshotProgramRevision = -1;
        private RetainedSceneSnapshot.Mesh snapshot;

        ReadyClaim(ReadyState<N> state) { this(state, state.initial); }

        ReadyClaim(ReadyState<N> state, SharedResource<ReadyState<N>> owner) {
            this.state = state;
            this.owner = owner;
        }

        /** Called with the directory and program locks held; the snapshot borrows this claim. */
        RetainedSceneSnapshot.Mesh snapshot(long programRevision) {
            if (snapshotProgramRevision != programRevision) {
                snapshot = state.directory.meshSnapshot(state.mesh.identity(), state.mesh.build(), this);
                snapshotProgramRevision = programRevision;
            }
            return snapshot;
        }

        @Override
        public ShaderDataType<N> instanceDataType() {
            return state.type;
        }

        @Override
        public ReadyMesh<N> retain() {
            return new ReadyClaim<>(state, owner.retain());
        }

        @Override
        public void close() {
            if (producer != null) {
                synchronized (state.directory) {
                    producer.meshes.remove(this);
                }
            }
            owner.close();
        }
    }
}
