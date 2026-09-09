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
import dev.comfyfluffy.caustica.support.SharedResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Serializes ready-reference edits and publishes immutable roots through a separate capture boundary. */
public final class SceneDirectory {
    private final ProgramSession programs;
    private final ResourceDirectory resources;
    private final RetainedSceneBackend backend;
    private final MeshPreparationBackend preparation;
    private final Map<SceneRef, LinkedHashMap<Object, RetainedEnvironment>> scenes = new LinkedHashMap<>();
    private final Map<InstanceRef, RetainedInstance> instances = new LinkedHashMap<>();
    private final Map<LightRef, RetainedLight> lights = new LinkedHashMap<>();
    private final SnapshotPages<RetainedSceneSnapshot.Scene> scenePages = new SnapshotPages<>();
    private final SnapshotPages<RetainedSceneSnapshot.Instance> instancePages = new SnapshotPages<>();
    private final SnapshotPages<RetainedSceneSnapshot.Mesh> meshPages = new SnapshotPages<>();
    private final SnapshotPages<RetainedSceneSnapshot.Light> lightPages = new SnapshotPages<>();
    private final Map<Long, MeshUse> meshUses = new LinkedHashMap<>();
    private final Object publicationLock = new Object();
    private SharedResource<RetainedSceneSnapshot> published;
    private long identity;
    // Per-kind ordering keeps unrelated identities from leaving nearly empty snapshot pages.
    private long instanceOrdinal;
    private long sceneOrdinal;
    private long meshOrdinal;
    private long lightOrdinal;
    private long revision;

    public SceneDirectory(ProgramSession programs,
            ResourceDirectory resources,
            RetainedSceneBackend backend,
            MeshPreparationBackend preparation) {
        this.programs = Objects.requireNonNull(programs);
        this.resources = Objects.requireNonNull(resources);
        this.backend = Objects.requireNonNull(backend);
        this.preparation = Objects.requireNonNull(preparation);
        publishSnapshot();
        backend.bind(this::capture);
    }

    public synchronized SceneId createScene() {
        SceneRef scene = new SceneRef(this, ++identity, sceneOrdinal++);
        scenes.put(scene, new LinkedHashMap<>());
        updateScene(scene);
        changed();
        return scene;
    }

    public synchronized void dropScene(SceneId id) {
        SceneRef scene = requireScene(id);
        removeInstances(entry -> entry.getValue().value.scene() == scene);
        removeLights(entry -> entry.getValue().value.scene() == scene);
        scenes.remove(scene).values().forEach(RetainedEnvironment::close);
        scenePages.remove(scene.ordinal);
        changed();
    }

    public SceneContributionChannel openChannel() {
        return new SceneContributionChannel(this);
    }

    public synchronized SceneEnvironmentContributionChannel openEnvironment(SceneId scene) {
        requireScene(scene);
        return new SceneEnvironmentContributionChannel(this, scene);
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
        long ordinal;
        synchronized (this) {
            requireCreation(channel);
            Objects.requireNonNull(type);
            Objects.requireNonNull(build);
            validateBuild(type, build);
            if (source != null) {
                requireReady(source);
                if (source.instanceDataType() != type) {
                    throw new IllegalArgumentException("refit schema mismatch");
                }
            }
            inputs = new ResourceOwners();
            sourceClaim = source == null ? null : source.retain();
            try { mesh = new RetainedSceneSnapshot.Mesh(++identity, inputs.mesh(build), null); }
            catch (Throwable failure) {
                inputs.close();
                if (sourceClaim != null) sourceClaim.close();
                throw failure;
            }
            ordinal = meshOrdinal++;
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
                        ReadyState<N> state = new ReadyState<>(this, mesh, ordinal, type, nativeOwner, inputs);
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
                validateInstance(set);
                RetainedInstance current = changedInstances.containsKey(id) ? changedInstances.get(id) : instances.get(id);
                changedInstances.put(id, new RetainedInstance(set,
                        current == null ? instanceOrdinal++ : current.placementOrdinal, null, primitiveEmitters(set)));
            } else if (edit instanceof SceneEdit.DropInstance drop) {
                changedInstances.put(requireInstance(channel, drop.instance()), null);
            } else if (edit instanceof SceneEdit.SetTransform set) {
                InstanceRef id = requireInstance(channel, set.instance());
                RetainedInstance current = changedInstances.containsKey(id) ? changedInstances.get(id) : instances.get(id);
                if (current == null) throw new IllegalArgumentException("transform names absent instance");
                changedInstances.put(id, new RetainedInstance(withTransform(current.value, set),
                        current.placementOrdinal, current.resources, current.snapshot.primitiveEmitters()));
            } else if (edit instanceof SceneEdit.SetLight set) {
                LightRef id = requireLight(channel, set.light());
                requireScene(set.scene());
                changedLights.put(id, set);
            } else if (edit instanceof SceneEdit.DropLight drop) {
                changedLights.put(requireLight(channel, drop.light()), null);
            } else if (edit instanceof SceneEdit.SetEnvironment set) {
                SceneRef scene = validateEnvironment(set.scene(), set.binding());
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
                        SharedResource.owned(owners, ResourceOwners::close), entry.snapshot.primitiveEmitters());
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
            if (previous != null && (entry == null || previous.placementOrdinal != entry.placementOrdinal)) {
                instancePages.remove(previous.placementOrdinal);
            }
            if (entry != null) instancePages.put(entry.placementOrdinal, entry.snapshot, entry.resources);
            if (previous == null || entry == null || previous.snapshot.meshIdentity() != entry.snapshot.meshIdentity()) {
                if (entry != null) addMesh(entry);
                if (previous != null) removeMesh(previous);
            }
            if (previous != null && (entry == null || previous.resources != entry.resources)) retired.add(previous.resources);
        });
        changedLights.forEach((id, value) -> {
            var previous = lights.get(id);
            if (value == null) {
                if (previous != null) {
                    lights.remove(id);
                    lightPages.remove(previous.ordinal);
                }
            } else {
                long ordinal = previous == null ? lightOrdinal++ : previous.ordinal;
                var light = new RetainedSceneSnapshot.Light(id.identity, value.scene(), value.descriptor());
                lights.put(id, new RetainedLight(ordinal, light));
                lightPages.put(ordinal, light);
            }
        });
        environments.forEach((scene, entry) -> replaceEnvironment(scene, channel, entry));
        changed();
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

    private void validateInstance(SceneEdit.SetInstance<?> instance) {
        requireScene(instance.scene());
        ReadyState<?> mesh = requireReady(instance.mesh());
        mesh.type.require(instance.instanceData());
        resources.validate(instance.instanceData().resource());
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
        SceneRef scene = validateEnvironment(channel.scene, binding);
        replaceEnvironment(scene, channel,
                ownEnvironment(binding));
        changed();
    }

    private SceneRef validateEnvironment(SceneId id, EnvironmentBinding<?> binding) {
        SceneRef scene = requireScene(id);
        programs.validateEnvironment(binding.implementation(), binding.bindingData());
        resources.validate(binding.bindingData().resource());
        return scene;
    }

    private void replaceEnvironment(SceneRef scene, Object slot, RetainedEnvironment entry) {
        var values = scenes.get(scene);
        // Reselecting a slot makes it current; removing it restores the most recent surviving selection.
        RetainedEnvironment previous = values.remove(slot);
        values.put(slot, entry);
        updateScene(scene);
        if (previous != null) previous.close();
    }

    private void removeInstances(java.util.function.Predicate<Map.Entry<InstanceRef, RetainedInstance>> predicate) {
        instances.entrySet().removeIf(entry -> {
            if (!predicate.test(entry)) return false;
            instancePages.remove(entry.getValue().placementOrdinal);
            removeMesh(entry.getValue());
            entry.getValue().resources.close();
            return true;
        });
    }

    private void removeLights(java.util.function.Predicate<Map.Entry<LightRef, RetainedLight>> predicate) {
        lights.entrySet().removeIf(entry -> {
            if (!predicate.test(entry)) return false;
            lightPages.remove(entry.getValue().ordinal);
            return true;
        });
    }

    private void removeEnvironments(Object slot) {
        scenes.forEach((scene, values) -> {
            RetainedEnvironment previous = values.remove(slot);
            if (previous != null) {
                updateScene(scene);
                previous.close();
            }
        });
    }

    synchronized void quiesce(SceneContributionChannel channel) {
        channel.acceptingIdentities = false;
    }

    synchronized void invalidate(SceneContributionChannel channel) {
        if (!channel.acceptingEdits) return;
        quiesce(channel);
        removeInstances(entry -> entry.getKey().owner == channel);
        removeLights(entry -> entry.getKey().owner == channel);
        removeEnvironments(channel);
        changed();
        channel.acceptingEdits = false;
        var claims = List.copyOf(channel.meshes);
        channel.meshes.clear();
        claims.forEach(ReadyMesh::close);
    }

    synchronized void invalidate(SceneEnvironmentContributionChannel channel) {
        if (!channel.accepting) return;
        removeEnvironments(channel);
        changed();
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

    public void settleFrameUses() { backend.settleFrameUses(); }

    public void prepareForSessionClose() { backend.prepareForSessionClose(); }

    private void changed() {
        revision++;
        publishSnapshot();
    }

    /** Commits only edited page paths before exposing one coherent cross-scene revision. */
    private void publishSnapshot() {
        scenePages.commit();
        instancePages.commit();
        meshPages.commit();
        lightPages.commit();
        var sceneClaim = scenePages.capture();
        var instanceClaim = instancePages.capture();
        var meshClaim = meshPages.capture();
        var lightClaim = lightPages.capture();
        var value = new RetainedSceneSnapshot(revision, sceneClaim.get(), meshClaim.get(),
                instanceClaim.get(), lightClaim.get());
        var replacement = SharedResource.owned(value, ignored -> {
            sceneClaim.close();
            instanceClaim.close();
            meshClaim.close();
            lightClaim.close();
        });
        SharedResource<RetainedSceneSnapshot> previous;
        synchronized (publicationLock) {
            previous = published;
            published = replacement;
        }
        if (previous != null) previous.close();
    }

    private void addMesh(RetainedInstance instance) {
        long id = instance.snapshot.meshIdentity();
        var use = meshUses.get(id);
        if (use != null) { use.instances++; return; }
        var ready = (ReadyClaim<?>) instance.value.mesh().retain();
        use = new MeshUse(ready);
        meshUses.put(id, use);
        meshPages.put(ready.state.ordinal, ready.snapshot, use.owner);
    }

    private void removeMesh(RetainedInstance instance) {
        long id = instance.snapshot.meshIdentity();
        var use = meshUses.get(id);
        if (--use.instances != 0) return;
        meshUses.remove(id);
        meshPages.remove(use.ready.state.ordinal);
        use.owner.close();
    }

    private static final class MeshUse {
        final ReadyClaim<?> ready;
        final SharedResource<ReadyMesh<?>> owner;
        int instances = 1;
        MeshUse(ReadyClaim<?> ready) {
            this.ready = ready;
            owner = SharedResource.owned(ready, ReadyMesh::close);
        }
    }

    /** Borrowed diagnostic values; callers needing stable resource lifetime use capture(). */
    public RetainedSceneSnapshot snapshot() {
        try (var claim = capture()) { return claim.get(); }
    }

    /** Acquires a published root without taking the edit lock or traversing any scene pages. */
    public SharedResource<RetainedSceneSnapshot> capture() {
        synchronized (publicationLock) { return published.retain(); }
    }

    private void updateScene(SceneRef scene) {
        var values = scenes.get(scene);
        var selected = values.isEmpty() ? null : values.lastEntry().getValue();
        scenePages.put(scene.ordinal, new RetainedSceneSnapshot.Scene(scene,
                selected == null ? null : selected.binding), selected == null ? null : selected.owner);
    }

    private record RetainedLight(long ordinal, RetainedSceneSnapshot.Light value) { }

    private static final class RetainedInstance {
        final SceneEdit.SetInstance<?> value;
        final long placementOrdinal;
        final SharedResource<ResourceOwners> resources;
        final RetainedSceneSnapshot.Instance snapshot;

        RetainedInstance(SceneEdit.SetInstance<?> value, long placementOrdinal,
                         SharedResource<ResourceOwners> resources,
                         List<RetainedSceneSnapshot.PrimitiveEmitter> primitiveEmitters) {
            this.value = value;
            this.placementOrdinal = placementOrdinal;
            this.resources = resources;
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

    private void validateBuild(ShaderDataType<?> type, MeshBuild<?> build) {
        resources.validate(build.positions().resource());
        resources.validate(build.indices().resource());
        for (var geometry : build.geometries()) {
            var surface = geometry.surface();
            if (surface != null) {
                resources.validate(surface.bindingData().resource());
                programs.validateSurface(surface.surface(), surface.bindingData(), type,
                        !(surface.coverage() instanceof MeshBuild.CoveragePolicy.Opaque));
            }
            var volume = geometry.volume();
            if (volume != null) {
                resources.validate(volume.bindingData().resource());
                programs.validateVolume(volume.volume(), volume.bindingData(), type);
            }
        }
    }

    private SceneRef requireScene(SceneId id) {
        if (!(id instanceof SceneRef scene) || scene.directory != this || !scenes.containsKey(scene)) {
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

    private record SceneRef(SceneDirectory directory, long identity, long ordinal) implements SceneId {
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
        final long ordinal;
        final ShaderDataType<N> type;
        final ResourceOwner nativeOwner;
        final SharedResource<ReadyState<N>> initial;

        ReadyState(SceneDirectory directory,
                RetainedSceneSnapshot.Mesh mesh,
                long ordinal,
                ShaderDataType<N> type,
                ResourceOwner nativeOwner,
                ResourceOwners inputs) {
            this.directory = directory;
            this.mesh = mesh;
            this.ordinal = ordinal;
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
        private final RetainedSceneSnapshot.Mesh snapshot;

        ReadyClaim(ReadyState<N> state) { this(state, state.initial); }

        ReadyClaim(ReadyState<N> state, SharedResource<ReadyState<N>> owner) {
            this.state = state;
            this.owner = owner;
            snapshot = new RetainedSceneSnapshot.Mesh(state.mesh.identity(), state.mesh.build(), this);
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
