package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.program.ProgramResolution;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/** Host-owned scenes and the session-wide retained geometry/light collections targeting them. */
public final class SceneDirectory {
    private final ProgramSession programs;
    private final RetainedSceneBackend backend;
    private final SceneRetirementFailureHandler failures;
    private final Map<SceneRef, Boolean> scenes = new LinkedHashMap<>();
    private Map<MeshRef, MeshValue> meshes = new LinkedHashMap<>();
    private Map<InstanceRef, InstanceValue> instances = new LinkedHashMap<>();
    private Map<LightRef, LightValue> lights = new LinkedHashMap<>();
    private Map<SceneRef, EnvironmentValue> environments = new LinkedHashMap<>();
    private Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>>
            environmentSelections = new LinkedHashMap<>();
    private final Queue<CallbackTask> callbacks = new ArrayDeque<>();
    private final Set<BatchToken> batches = new LinkedHashSet<>();
    private boolean backendProgressAvailable;
    private long nextIdentity;
    private long revision;

    public SceneDirectory(ProgramSession programs, RetainedSceneBackend backend,
                          SceneRetirementFailureHandler failures) {
        this.programs = Objects.requireNonNull(programs, "programs");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.failures = Objects.requireNonNull(failures, "failures");
        backend.onProgressAvailable(this::signalBackendProgress);
    }

    /** Host-only scene creation. Public extension contexts never receive this authority. */
    public synchronized SceneId createScene() {
        SceneRef scene = new SceneRef(this, ++nextIdentity);
        scenes.put(scene, Boolean.TRUE);
        try {
            publish(List.of(), null);
        } catch (Throwable failure) {
            scenes.remove(scene);
            throw failure;
        }
        return scene;
    }

    /** Host-only scene removal, cascading every placement and light which targets it. */
    public synchronized void dropScene(SceneId id) {
        SceneRef scene = requireLiveScene(id);
        Map<InstanceRef, InstanceValue> nextInstances = new LinkedHashMap<>(instances);
        Map<LightRef, LightValue> nextLights = new LinkedHashMap<>(lights);
        Map<SceneRef, EnvironmentValue> nextEnvironments = new LinkedHashMap<>(environments);
        Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>>
                nextEnvironmentSelections = new LinkedHashMap<>(environmentSelections);
        List<RetainedValue> removed = new ArrayList<>();
        nextInstances.entrySet().removeIf(entry -> {
            if (entry.getValue().scene != scene) return false;
            removed.add(entry.getValue());
            return true;
        });
        nextLights.entrySet().removeIf(entry -> {
            if (entry.getValue().scene != scene) return false;
            removed.add(entry.getValue());
            return true;
        });
        EnvironmentValue environment = nextEnvironments.remove(scene);
        if (environment != null) removed.add(environment);
        LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue> selections =
                nextEnvironmentSelections.remove(scene);
        List<EnvironmentValue> dormant = selections == null ? List.of() : selections.values().stream()
                .filter(value -> value != environment).toList();
        Map<InstanceRef, InstanceValue> previousInstances = instances;
        Map<LightRef, LightValue> previousLights = lights;
        Map<SceneRef, EnvironmentValue> previousEnvironments = environments;
        Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>>
                previousEnvironmentSelections = environmentSelections;
        scenes.remove(scene);
        instances = nextInstances;
        lights = nextLights;
        environments = nextEnvironments;
        environmentSelections = nextEnvironmentSelections;
        try {
            publish(removed, null);
        } catch (Throwable failure) {
            scenes.put(scene, Boolean.TRUE);
            instances = previousInstances;
            lights = previousLights;
            environments = previousEnvironments;
            environmentSelections = previousEnvironmentSelections;
            throw failure;
        }
        dormant.forEach(value -> value.token.release(this));
    }

    public synchronized GeometryContributionChannel openGeometry(ContributionOwner owner) {
        return new GeometryContributionChannel(this, Objects.requireNonNull(owner, "owner"));
    }

    public synchronized LightContributionChannel openLights(ContributionOwner owner) {
        return new LightContributionChannel(this, Objects.requireNonNull(owner, "owner"));
    }

    public synchronized SceneEnvironmentContributionChannel openEnvironment(
            ContributionOwner owner, SceneId scene) {
        requireLiveScene(scene);
        return new SceneEnvironmentContributionChannel(
                this, Objects.requireNonNull(owner, "owner"), scene);
    }

    public synchronized RetainedSceneSnapshot snapshot() { return snapshot(revision); }

    /** Advances native publications and runs their serialized retirement callbacks. */
    public void progress() {
        synchronized (this) { backendProgressAvailable = false; }
        backend.progress();
        while (true) {
            CallbackTask callback;
            synchronized (this) { callback = callbacks.poll(); }
            if (callback == null) return;
            try {
                callback.callback.run();
            } catch (Throwable failure) {
                failures.report(failure);
            } finally {
                synchronized (this) {
                    if (callback.completed != null) batches.remove(callback.completed);
                    notifyAll();
                }
            }
        }
    }

    synchronized <N> MeshId<N> newMesh(GeometryContributionChannel channel, ShaderDataType<N> type) {
        requireIdentityCreation(channel);
        return new MeshRef<>(this, channel, ++nextIdentity, Objects.requireNonNull(type, "instanceDataType"));
    }

    synchronized InstanceId newInstance(GeometryContributionChannel channel) {
        requireIdentityCreation(channel);
        return new InstanceRef(this, channel, ++nextIdentity);
    }

    synchronized LightId newLight(LightContributionChannel channel) {
        requireIdentityCreation(channel);
        return new LightRef(this, channel, ++nextIdentity);
    }

    synchronized void submitGeometry(GeometryContributionChannel channel,
                                     RetainedBatch<GeometryChannel.Operation> batch) {
        requireSubmission(channel);
        Objects.requireNonNull(batch, "batch");
        validateGeometry(channel, batch.operations());
        BatchToken token = new BatchToken(channel, batch.retired());
        Map<MeshRef, MeshValue> nextMeshes = new LinkedHashMap<>(meshes);
        Map<InstanceRef, InstanceValue> nextInstances = new LinkedHashMap<>(instances);
        List<RetainedValue> removed = new ArrayList<>();
        for (GeometryChannel.Operation operation : batch.operations()) {
            if (operation instanceof GeometryChannel.SetMesh<?> set) {
                MeshRef mesh = (MeshRef) set.mesh();
                replace(nextMeshes, mesh, new MeshValue(token, set.build()), removed);
            } else if (operation instanceof GeometryChannel.DropMesh<?> drop) {
                MeshRef mesh = (MeshRef) drop.mesh();
                remove(nextMeshes, mesh, removed);
                nextInstances.entrySet().removeIf(entry -> {
                    if (entry.getValue().mesh != mesh) return false;
                    removed.add(entry.getValue());
                    return true;
                });
            } else if (operation instanceof GeometryChannel.SetInstance<?> set) {
                replace(nextInstances, (InstanceRef) set.instance(),
                        new InstanceValue(token, (SceneRef) set.scene(), (MeshRef) set.mesh(), set), removed);
            } else if (operation instanceof GeometryChannel.DropInstance drop) {
                remove(nextInstances, (InstanceRef) drop.instance(), removed);
            }
        }
        RetainedSceneSnapshot next = snapshot(revision + 1, nextMeshes, nextInstances, lights);
        backend.publish(next, () -> enqueueRelease(removed));
        batches.add(token);
        meshes = nextMeshes;
        instances = nextInstances;
        revision++;
        token.seal(this);
    }

    synchronized void submitLights(LightContributionChannel channel,
                                   RetainedBatch<LightChannel.Operation> batch) {
        requireSubmission(channel);
        Objects.requireNonNull(batch, "batch");
        Map<LightRef, Boolean> simulated = new LinkedHashMap<>();
        lights.keySet().forEach(light -> simulated.put(light, Boolean.TRUE));
        for (LightChannel.Operation operation : batch.operations()) {
            if (operation instanceof LightChannel.SetLight set) {
                requireOwnedLight(channel, set.light());
                requireLiveScene(set.scene());
                simulated.put((LightRef) set.light(), Boolean.TRUE);
            } else if (operation instanceof LightChannel.DropLight drop) {
                requireOwnedLight(channel, drop.light());
                simulated.remove((LightRef) drop.light());
            }
        }
        BatchToken token = new BatchToken(channel, batch.retired());
        Map<LightRef, LightValue> nextLights = new LinkedHashMap<>(lights);
        List<RetainedValue> removed = new ArrayList<>();
        for (LightChannel.Operation operation : batch.operations()) {
            if (operation instanceof LightChannel.SetLight set) {
                replace(nextLights, (LightRef) set.light(),
                        new LightValue(token, (SceneRef) set.scene(), set.descriptor()), removed);
            } else if (operation instanceof LightChannel.DropLight drop) {
                remove(nextLights, (LightRef) drop.light(), removed);
            }
        }
        RetainedSceneSnapshot next = snapshot(revision + 1, meshes, instances, nextLights);
        backend.publish(next, () -> enqueueRelease(removed));
        batches.add(token);
        lights = nextLights;
        revision++;
        token.seal(this);
    }

    synchronized void selectEnvironment(SceneEnvironmentContributionChannel channel,
                                        EnvironmentBinding<?> binding) {
        requireEnvironmentChannel(channel);
        if (!channel.accepting) throw new IllegalStateException("environment channel is invalidated");
        Objects.requireNonNull(binding, "binding");
        SceneRef scene = requireLiveScene(channel.scene);
        programs.validateEnvironment(binding.implementation(), binding.bindingData());
        BatchToken token = new BatchToken(channel, binding.retired());
        token.retain();
        LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue> selections =
                new LinkedHashMap<>(environmentSelections.getOrDefault(scene, new LinkedHashMap<>()));
        EnvironmentValue replaced = selections.remove(channel);
        EnvironmentValue selected = new EnvironmentValue(token, binding);
        selections.put(channel, selected);
        EnvironmentValue previous = environments.get(scene);
        boolean previousSurvives = previous != null && selections.containsValue(previous);
        // A surviving slot and the retiring publication independently keep the displaced binding alive.
        if (previousSurvives) previous.token.retain();
        Map<SceneRef, EnvironmentValue> nextEnvironments = new LinkedHashMap<>(environments);
        nextEnvironments.put(scene, selected);
        List<RetainedValue> removed = previous != null ? List.of(previous) : List.of();
        RetainedSceneSnapshot next = snapshot(revision + 1, meshes, instances, lights, nextEnvironments);
        try {
            backend.publish(next, () -> enqueueRelease(removed));
        } catch (Throwable failure) {
            if (previousSurvives) previous.token.release(this);
            throw failure;
        }
        batches.add(token);
        Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>> nextSelections =
                new LinkedHashMap<>(environmentSelections);
        nextSelections.put(scene, selections);
        environmentSelections = nextSelections;
        environments = nextEnvironments;
        revision++;
        token.seal(this);
        if (replaced != null && replaced != previous) replaced.token.release(this);
    }

    synchronized void quiesce(GeometryContributionChannel channel) {
        requireChannel(channel);
        channel.acceptingIdentities = false;
    }
    synchronized void quiesce(LightContributionChannel channel) {
        requireChannel(channel);
        channel.acceptingIdentities = false;
    }

    synchronized void invalidate(GeometryContributionChannel channel) {
        quiesce(channel);
        channel.acceptingSubmissions = false;
        Map<MeshRef, MeshValue> nextMeshes = new LinkedHashMap<>(meshes);
        Map<InstanceRef, InstanceValue> nextInstances = new LinkedHashMap<>(instances);
        List<RetainedValue> removed = new ArrayList<>();
        Set<MeshRef> ownedMeshes = new LinkedHashSet<>();
        nextMeshes.entrySet().removeIf(entry -> {
            if (entry.getKey().owner != channel) return false;
            ownedMeshes.add(entry.getKey()); removed.add(entry.getValue()); return true;
        });
        nextInstances.entrySet().removeIf(entry -> {
            if (entry.getKey().owner != channel && !ownedMeshes.contains(entry.getValue().mesh)) return false;
            removed.add(entry.getValue()); return true;
        });
        if (removed.isEmpty()) return;
        RetainedSceneSnapshot next = snapshot(revision + 1, nextMeshes, nextInstances, lights);
        backend.publish(next, () -> enqueueRelease(removed));
        meshes = nextMeshes; instances = nextInstances; revision++;
    }

    synchronized void invalidate(LightContributionChannel channel) {
        quiesce(channel);
        channel.acceptingSubmissions = false;
        Map<LightRef, LightValue> nextLights = new LinkedHashMap<>(lights);
        List<RetainedValue> removed = new ArrayList<>();
        nextLights.entrySet().removeIf(entry -> {
            if (entry.getKey().owner != channel) return false;
            removed.add(entry.getValue()); return true;
        });
        if (removed.isEmpty()) return;
        RetainedSceneSnapshot next = snapshot(revision + 1, meshes, instances, nextLights);
        backend.publish(next, () -> enqueueRelease(removed));
        lights = nextLights; revision++;
    }

    synchronized void invalidate(SceneEnvironmentContributionChannel channel) {
        requireEnvironmentChannel(channel);
        channel.accepting = false;
        SceneRef scene = requireLiveScene(channel.scene);
        LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue> currentSelections =
                environmentSelections.get(scene);
        if (currentSelections == null || !currentSelections.containsKey(channel)) return;
        LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue> nextSceneSelections =
                new LinkedHashMap<>(currentSelections);
        EnvironmentValue removed = nextSceneSelections.remove(channel);
        EnvironmentValue current = environments.get(scene);
        Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>> nextSelections =
                new LinkedHashMap<>(environmentSelections);
        if (nextSceneSelections.isEmpty()) nextSelections.remove(scene);
        else nextSelections.put(scene, nextSceneSelections);
        if (removed != current) {
            environmentSelections = nextSelections;
            removed.token.release(this);
            return;
        }
        Map<SceneRef, EnvironmentValue> nextEnvironments = new LinkedHashMap<>(environments);
        EnvironmentValue restored = lastValue(nextSceneSelections);
        if (restored == null) nextEnvironments.remove(scene);
        else nextEnvironments.put(scene, restored);
        RetainedSceneSnapshot next = snapshot(revision + 1, meshes, instances, lights, nextEnvironments);
        try {
            backend.publish(next, () -> enqueueRelease(List.of(current)));
        } catch (Throwable failure) {
            channel.accepting = true;
            throw failure;
        }
        environmentSelections = nextSelections;
        environments = nextEnvironments;
        revision++;
    }

    public void drain(GeometryContributionChannel channel) { drainOwner(channel); }
    public void drain(LightContributionChannel channel) { drainOwner(channel); }
    public void drain(SceneEnvironmentContributionChannel channel) { drainOwner(channel); }

    private void drainOwner(Object owner) {
        while (true) {
            progress();
            synchronized (this) {
                if (batches.stream().noneMatch(batch -> batch.owner == owner)) return;
                if (!callbacks.isEmpty()) continue;
                if (backendProgressAvailable) continue;
                awaitChange();
            }
        }
    }

    private synchronized void signalBackendProgress() {
        backendProgressAvailable = true;
        notifyAll();
    }

    private void validateGeometry(GeometryContributionChannel channel, List<GeometryChannel.Operation> operations) {
        Map<MeshRef, MeshBuild<?>> simulatedMeshes = new LinkedHashMap<>();
        meshes.forEach((mesh, value) -> simulatedMeshes.put(mesh, value.build));
        Map<InstanceRef, MeshRef> simulatedInstances = new LinkedHashMap<>();
        instances.forEach((id, value) -> simulatedInstances.put(id, value.mesh));
        for (GeometryChannel.Operation operation : operations) {
            if (operation instanceof GeometryChannel.SetMesh<?> set) {
                MeshRef<?> mesh = requireOwnedMesh(channel, set.mesh());
                validateBuild(mesh, set.build());
                simulatedMeshes.put(mesh, set.build());
            } else if (operation instanceof GeometryChannel.DropMesh<?> drop) {
                MeshRef<?> mesh = requireOwnedMesh(channel, drop.mesh());
                simulatedMeshes.remove(mesh);
                simulatedInstances.entrySet().removeIf(entry -> entry.getValue() == mesh);
            } else if (operation instanceof GeometryChannel.SetInstance<?> set) {
                InstanceRef instance = requireOwnedInstance(channel, set.instance());
                SceneRef scene = requireLiveScene(set.scene());
                MeshRef<?> mesh = requireOwnedMesh(channel, set.mesh());
                MeshBuild<?> build = simulatedMeshes.get(mesh);
                if (build == null) throw new IllegalArgumentException("instance names an absent mesh");
                mesh.instanceType.require(set.instanceData());
                set.primitiveLights().ranges().forEach(range -> requireLightSelection(range.light()));
                validatePrimitiveLights(build, set.primitiveLights());
                simulatedInstances.put(instance, mesh);
            } else if (operation instanceof GeometryChannel.DropInstance drop) {
                simulatedInstances.remove(requireOwnedInstance(channel, drop.instance()));
            }
        }
    }

    private static void validatePrimitiveLights(MeshBuild<?> build,
                                                dev.comfyfluffy.caustica.api.geometry.PrimitiveLightMap map) {
        int triangleCount = build.geometries().stream()
                .mapToInt(geometry -> Math.addExact(geometry.firstIndex(), geometry.indexCount()) / 3)
                .max().orElseThrow();
        for (var range : map.ranges()) {
            if (Math.addExact(range.firstPrimitive(), range.primitiveCount()) > triangleCount) {
                throw new IllegalArgumentException("primitive-light range exceeds the selected mesh");
            }
        }
    }

    private void validateBuild(MeshRef<?> mesh, MeshBuild<?> build) {
        for (MeshBuild.Geometry<?> geometry : build.geometries()) {
            if (geometry.surface() != null) programs.validateSurface(geometry.surface().surface(),
                    geometry.surface().bindingData(), mesh.instanceType,
                    geometry.surface().coverage() instanceof MeshBuild.CoveragePolicy.Cutout);
            if (geometry.volume() != null) programs.validateVolume(geometry.volume().volume(),
                    geometry.volume().bindingData(), mesh.instanceType);
        }
    }

    private RetainedSceneSnapshot snapshot(long revision) {
        return snapshot(revision, meshes, instances, lights, environments);
    }
    private RetainedSceneSnapshot snapshot(long revision, Map<MeshRef, MeshValue> meshValues,
                                            Map<InstanceRef, InstanceValue> instanceValues,
                                            Map<LightRef, LightValue> lightValues) {
        return snapshot(revision, meshValues, instanceValues, lightValues, environments);
    }
    private RetainedSceneSnapshot snapshot(long revision, Map<MeshRef, MeshValue> meshValues,
                                            Map<InstanceRef, InstanceValue> instanceValues,
                                            Map<LightRef, LightValue> lightValues,
                                            Map<SceneRef, EnvironmentValue> environmentValues) {
        return new RetainedSceneSnapshot(revision,
                scenes.keySet().stream().map(scene -> new RetainedSceneSnapshot.Scene(
                        scene, environmentValues.containsKey(scene)
                                ? environmentValues.get(scene).binding : null)).toList(),
                meshValues.entrySet().stream().map(entry ->
                        new RetainedSceneSnapshot.Mesh(entry.getKey().identity, entry.getValue().build,
                                entry.getValue().build.geometries().stream().map(geometry ->
                                        new RetainedSceneSnapshot.GeometryPrograms(
                                                geometry.surface() == null
                                                        ? ProgramResolution.ErrorSurface.INSTANCE
                                                        : programs.resolve(geometry.surface().surface()),
                                                geometry.volume() == null
                                                        ? ProgramResolution.Vacuum.INSTANCE
                                                        : programs.resolve(geometry.volume().volume())))
                                        .toList())).toList(),
                instanceValues.entrySet().stream().map(entry -> new RetainedSceneSnapshot.Instance(
                        entry.getKey().identity, entry.getValue().scene, entry.getValue().mesh.identity,
                        entry.getValue().operation.transform(), entry.getValue().operation.mask(),
                        entry.getValue().operation.instanceData(),
                        entry.getValue().operation.primitiveLights().ranges().stream().map(range ->
                                new RetainedSceneSnapshot.PrimitiveEmitter(range.firstPrimitive(),
                                        range.primitiveCount(), ((LightRef) range.light()).identity)).toList())).toList(),
                lightValues.entrySet().stream().map(entry -> new RetainedSceneSnapshot.Light(
                        entry.getKey().identity, entry.getValue().scene, entry.getValue().descriptor)).toList());
    }

    private void publish(List<RetainedValue> removed, BatchToken token) {
        RetainedSceneSnapshot next = snapshot(revision + 1);
        backend.publish(next, () -> enqueueRelease(removed));
        revision++;
        if (token != null) token.seal(this);
    }

    private synchronized void enqueueRelease(List<RetainedValue> values) {
        callbacks.add(new CallbackTask(null, () -> values.forEach(value -> value.token.release(this))));
        notifyAll();
    }
    private synchronized void enqueue(BatchToken token, Runnable callback) {
        callbacks.add(new CallbackTask(token, callback));
        notifyAll();
    }

    private void awaitChange() {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining retained scenes", interrupted);
        }
    }

    private SceneRef requireLiveScene(SceneId id) {
        if (!(id instanceof SceneRef scene) || scene.directory != this || !scenes.containsKey(scene))
            throw new IllegalArgumentException("scene is stale or belongs to another session");
        return scene;
    }
    private MeshRef<?> requireOwnedMesh(GeometryContributionChannel channel, MeshId<?> id) {
        if (!(id instanceof MeshRef<?> mesh) || mesh.directory != this || mesh.owner != channel)
            throw new IllegalArgumentException("mesh mutation id belongs to another contribution");
        return mesh;
    }
    private InstanceRef requireOwnedInstance(GeometryContributionChannel channel, InstanceId id) {
        if (!(id instanceof InstanceRef instance) || instance.directory != this || instance.owner != channel)
            throw new IllegalArgumentException("instance mutation id belongs to another contribution");
        return instance;
    }
    private LightRef requireOwnedLight(LightContributionChannel channel, LightId id) {
        if (!(id instanceof LightRef light) || light.directory != this || light.owner != channel)
            throw new IllegalArgumentException("light mutation id belongs to another contribution");
        return light;
    }
    private LightRef requireLightSelection(LightId id) {
        if (!(id instanceof LightRef light) || light.directory != this) {
            throw new IllegalArgumentException("light selection belongs to another render session");
        }
        return light;
    }
    private void requireIdentityCreation(GeometryContributionChannel channel) {
        requireChannel(channel);
        if (!channel.acceptingIdentities) throw new IllegalStateException("geometry identity creation is quiesced");
    }
    private void requireIdentityCreation(LightContributionChannel channel) {
        requireChannel(channel);
        if (!channel.acceptingIdentities) throw new IllegalStateException("light identity creation is quiesced");
    }
    private void requireSubmission(GeometryContributionChannel channel) {
        requireChannel(channel);
        if (!channel.acceptingSubmissions) throw new IllegalStateException("geometry channel is invalidated");
    }
    private void requireSubmission(LightContributionChannel channel) {
        requireChannel(channel);
        if (!channel.acceptingSubmissions) throw new IllegalStateException("light channel is invalidated");
    }
    private void requireChannel(GeometryContributionChannel channel) {
        if (channel == null || channel.directory != this) throw new IllegalArgumentException("foreign geometry channel");
    }
    private void requireChannel(LightContributionChannel channel) {
        if (channel == null || channel.directory != this) throw new IllegalArgumentException("foreign light channel");
    }
    private void requireEnvironmentChannel(SceneEnvironmentContributionChannel channel) {
        if (channel == null || channel.directory != this)
            throw new IllegalArgumentException("foreign environment channel");
    }

    private static <K, V extends RetainedValue> void replace(Map<K, V> map, K key, V value,
                                                              List<RetainedValue> removed) {
        value.token.retain();
        V previous = map.put(key, value);
        if (previous != null) removed.add(previous);
    }
    private static <K, V extends RetainedValue> void remove(Map<K, V> map, K key,
                                                             List<RetainedValue> removed) {
        V previous = map.remove(key);
        if (previous != null) removed.add(previous);
    }

    private static <K, V> V lastValue(LinkedHashMap<K, V> values) {
        V last = null;
        for (V value : values.values()) last = value;
        return last;
    }

    private static final class BatchToken {
        private final Object owner;
        private final Runnable callback;
        private int references;
        private boolean sealed;
        private boolean scheduled;
        BatchToken(Object owner, Runnable callback) { this.owner = owner; this.callback = callback; }
        void retain() { references++; }
        void release(SceneDirectory directory) { references--; scheduleIfDone(directory); }
        void seal(SceneDirectory directory) { sealed = true; scheduleIfDone(directory); }
        private void scheduleIfDone(SceneDirectory directory) {
            if (sealed && references == 0 && !scheduled) {
                scheduled = true;
                directory.enqueue(this, callback);
            }
        }
    }
    private record CallbackTask(BatchToken completed, Runnable callback) { }
    private abstract static class RetainedValue { final BatchToken token; RetainedValue(BatchToken token) { this.token = token; } }
    private static final class MeshValue extends RetainedValue { final MeshBuild<?> build; MeshValue(BatchToken t, MeshBuild<?> b) { super(t); build=b; } }
    private static final class InstanceValue extends RetainedValue {
        final SceneRef scene; final MeshRef mesh; final GeometryChannel.SetInstance<?> operation;
        InstanceValue(BatchToken t, SceneRef s, MeshRef m, GeometryChannel.SetInstance<?> o) { super(t);scene=s;mesh=m;operation=o; }
    }
    private static final class LightValue extends RetainedValue {
        final SceneRef scene; final dev.comfyfluffy.caustica.api.light.LightDescriptor descriptor;
        LightValue(BatchToken t, SceneRef s, dev.comfyfluffy.caustica.api.light.LightDescriptor d) { super(t);scene=s;descriptor=d; }
    }
    private static final class EnvironmentValue extends RetainedValue {
        final EnvironmentBinding<?> binding;
        EnvironmentValue(BatchToken token, EnvironmentBinding<?> binding) {
            super(token);
            this.binding = binding;
        }
    }
    private static final class SceneRef implements SceneId { final SceneDirectory directory; final long identity; SceneRef(SceneDirectory d,long i){directory=d;identity=i;} }
    private static final class MeshRef<N> implements MeshId<N> {
        final SceneDirectory directory; final GeometryContributionChannel owner; final long identity; final ShaderDataType<N> instanceType;
        MeshRef(SceneDirectory d,GeometryContributionChannel o,long i,ShaderDataType<N> t){directory=d;owner=o;identity=i;instanceType=t;}
    }
    private static final class InstanceRef implements InstanceId { final SceneDirectory directory; final GeometryContributionChannel owner; final long identity; InstanceRef(SceneDirectory d,GeometryContributionChannel o,long i){directory=d;owner=o;identity=i;} }
    private static final class LightRef implements LightId { final SceneDirectory directory; final LightContributionChannel owner; final long identity; LightRef(SceneDirectory d,LightContributionChannel o,long i){directory=d;owner=o;identity=i;} }
}
