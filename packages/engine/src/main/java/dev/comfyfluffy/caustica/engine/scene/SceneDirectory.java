package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.retained.RetainedPublication;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Host-owned scenes and the session-wide retained geometry/light collections targeting them. */
public final class SceneDirectory {
    private final ProgramSession programs;
    private final ResourceDirectory resources;
    private final RetainedSceneBackend backend;
    private final SceneRetirementFailureHandler failures;
    private final Map<SceneRef, Boolean> scenes = new LinkedHashMap<>();
    private Map<MeshRef, MeshValue> meshes = new LinkedHashMap<>();
    private Map<InstanceRef, InstanceValue> instances = new LinkedHashMap<>();
    private Map<LightRef, LightValue> lights = new LinkedHashMap<>();
    private Map<SceneRef, EnvironmentValue> environments = new LinkedHashMap<>();
    private Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>>
            environmentSelections = new LinkedHashMap<>();
    private final Set<PendingPublication> publications = new LinkedHashSet<>();
    private boolean backendProgressAvailable;
    private long nextIdentity;
    private long revision;

    public SceneDirectory(ProgramSession programs, ResourceDirectory resources, RetainedSceneBackend backend,
                          SceneRetirementFailureHandler failures) {
        this.programs = Objects.requireNonNull(programs, "programs");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.failures = Objects.requireNonNull(failures, "failures");
        backend.onProgressAvailable(this::signalBackendProgress);
    }

    /** Host-only scene creation. Public extension contexts never receive this authority. */
    public synchronized SceneId createScene() {
        SceneRef scene = new SceneRef(this, ++nextIdentity);
        scenes.put(scene, Boolean.TRUE);
        try {
            publish();
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
        nextInstances.entrySet().removeIf(entry -> entry.getValue().scene == scene);
        nextLights.entrySet().removeIf(entry -> entry.getValue().scene == scene);
        nextEnvironments.remove(scene);
        nextEnvironmentSelections.remove(scene);
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
            publish();
        } catch (Throwable failure) {
            scenes.put(scene, Boolean.TRUE);
            instances = previousInstances;
            lights = previousLights;
            environments = previousEnvironments;
            environmentSelections = previousEnvironmentSelections;
            throw failure;
        }
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

    /** Advances native publications. */
    public void progress() {
        synchronized (this) { backendProgressAvailable = false; }
        backend.progress();
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

    synchronized RetainedPublication submitGeometry(GeometryContributionChannel channel,
                                                     RetainedBatch<GeometryChannel.Operation> batch) {
        return submitGeometryGroup(channel, List.of(batch));
    }

    synchronized RetainedPublication submitGeometryGroup(GeometryContributionChannel channel,
                                                          List<RetainedBatch<GeometryChannel.Operation>> group) {
        return submitGeometryGroupWithLatest(channel, group, List.of());
    }

    synchronized RetainedPublication submitGeometryGroupWithLatest(
            GeometryContributionChannel channel,
            List<RetainedBatch<GeometryChannel.Operation>> group,
            List<GeometryChannel.LatestInstance> latestInstances) {
        requireSubmission(channel);
        group = List.copyOf(group);
        latestInstances = List.copyOf(latestInstances);
        if (group.isEmpty() && latestInstances.isEmpty()) {
            throw new IllegalArgumentException("a submission needs a retained batch or latest placement");
        }
        List<GeometryChannel.Operation> operations = group.stream()
                .flatMap(batch -> batch.operations().stream())
                .toList();
        validateGeometry(channel, operations);
        Map<MeshRef, MeshValue> nextMeshes = new LinkedHashMap<>(meshes);
        Map<InstanceRef, InstanceValue> nextInstances = new LinkedHashMap<>(instances);
        for (int batchIndex = 0; batchIndex < group.size(); batchIndex++) {
            applyGeometry(group.get(batchIndex).operations(),
                    nextMeshes, nextInstances);
        }
        List<RetainedInstanceTransform> latest = applyLatestInstances(
                channel, latestInstances, nextInstances);
        if (group.isEmpty()) {
            backend.updateLatestInstanceTransforms(latest);
            instances = nextInstances;
            return RetainedPublication.alreadyVisible();
        }
        long nextRevision = revision + 1;
        RetainedSceneGeometryDelta delta = geometryDelta(
                nextRevision, operations, latestInstances, nextInstances);
        PublicationReceipt receipt = new PublicationReceipt(failures);
        PendingPublication publication = beginPublication(channel);
        Map<LightRef, LightValue> currentLights = lights;
        Map<SceneRef, EnvironmentValue> currentEnvironments = environments;
        try {
            backend.publishGeometry(delta,
                    () -> snapshot(nextRevision, nextMeshes, nextInstances, currentLights, currentEnvironments),
                    () -> { receipt.publish(); completePublication(publication); });
        } catch (Throwable failure) {
            rejectPublication(publication);
            throw failure;
        }
        backend.updateLatestInstanceTransforms(latest);
        meshes = nextMeshes;
        instances = nextInstances;
        revision++;
        return receipt;
    }

    private List<RetainedInstanceTransform> applyLatestInstances(
            GeometryContributionChannel channel,
            List<GeometryChannel.LatestInstance> latestInstances,
            Map<InstanceRef, InstanceValue> nextInstances) {
        List<RetainedInstanceTransform> result = new ArrayList<>(latestInstances.size());
        for (GeometryChannel.LatestInstance latest : latestInstances) {
            InstanceRef instance = requireOwnedInstance(channel, latest.instance());
            InstanceValue current = nextInstances.get(instance);
            if (current == null) throw new IllegalArgumentException("latest placement names an absent instance");
            GeometryChannel.SetInstance<?> prior = current.operation;
            GeometryChannel.SetInstance<?> replacement = withLatestPlacement(prior, latest);
            nextInstances.put(instance, new InstanceValue(current.scene, current.mesh, replacement));
            result.add(new RetainedInstanceTransform(instance.identity, latest.transform(), latest.mask()));
        }
        return List.copyOf(result);
    }

    private static <N> GeometryChannel.SetInstance<N> withLatestPlacement(
            GeometryChannel.SetInstance<N> prior, GeometryChannel.LatestInstance latest) {
        return new GeometryChannel.SetInstance<>(prior.instance(), prior.scene(), prior.mesh(),
                latest.transform(), latest.mask(), prior.instanceData(), prior.primitiveLights());
    }

    synchronized RetainedPublication submitGeometryAndLights(
            GeometryContributionChannel geometryChannel, LightChannel lightChannel,
            List<RetainedBatch<GeometryChannel.Operation>> geometryGroup,
            RetainedBatch<LightChannel.Operation> lightBatch) {
        requireSubmission(geometryChannel);
        if (!(lightChannel instanceof LightContributionChannel ownedLights)) {
            throw new IllegalArgumentException("foreign light channel");
        }
        requireSubmission(ownedLights);
        if (geometryChannel.owner != ownedLights.owner) {
            throw new IllegalArgumentException("geometry and light channels belong to different contributions");
        }
        geometryGroup = List.copyOf(geometryGroup);
        if (geometryGroup.isEmpty()) {
            throw new IllegalArgumentException("a submission group needs at least one geometry batch");
        }
        Objects.requireNonNull(lightBatch, "lightBatch");
        List<GeometryChannel.Operation> geometryOperations = geometryGroup.stream()
                .flatMap(batch -> batch.operations().stream())
                .toList();
        validateGeometry(geometryChannel, geometryOperations);
        validateLights(ownedLights, lightBatch.operations());

        Map<MeshRef, MeshValue> nextMeshes = new LinkedHashMap<>(meshes);
        Map<InstanceRef, InstanceValue> nextInstances = new LinkedHashMap<>(instances);
        for (int batchIndex = 0; batchIndex < geometryGroup.size(); batchIndex++) {
            applyGeometry(geometryGroup.get(batchIndex).operations(),
                    nextMeshes, nextInstances);
        }
        Map<LightRef, LightValue> nextLights = new LinkedHashMap<>(lights);
        applyLights(lightBatch.operations(), nextLights);

        long nextRevision = revision + 1;
        RetainedSceneGeometryDelta geometryDelta = geometryDelta(nextRevision, geometryOperations);
        RetainedSceneContentSnapshot content = contentSnapshot(nextRevision, nextLights, environments);
        PublicationReceipt receipt = new PublicationReceipt(failures);
        PendingPublication publication = beginPublication(geometryChannel, ownedLights);
        Map<SceneRef, EnvironmentValue> currentEnvironments = environments;
        try {
            backend.publishGeometryAndContent(geometryDelta, content,
                    () -> snapshot(nextRevision, nextMeshes, nextInstances, nextLights, currentEnvironments),
                    () -> { receipt.publish(); completePublication(publication); });
        } catch (Throwable failure) {
            rejectPublication(publication);
            throw failure;
        }
        meshes = nextMeshes;
        instances = nextInstances;
        lights = nextLights;
        revision++;
        return receipt;
    }

    synchronized void submitLights(LightContributionChannel channel,
                                   RetainedBatch<LightChannel.Operation> batch) {
        requireSubmission(channel);
        Objects.requireNonNull(batch, "batch");
        validateLights(channel, batch.operations());
        Map<LightRef, LightValue> nextLights = new LinkedHashMap<>(lights);
        applyLights(batch.operations(), nextLights);
        RetainedSceneContentSnapshot next = contentSnapshot(revision + 1, nextLights, environments);
        PendingPublication publication = beginPublication(channel);
        try {
            backend.publishContent(next, () -> completePublication(publication));
        } catch (Throwable failure) {
            rejectPublication(publication);
            throw failure;
        }
        lights = nextLights;
        revision++;
    }

    private void validateLights(LightContributionChannel channel,
                                List<LightChannel.Operation> operations) {
        for (LightChannel.Operation operation : operations) {
            if (operation instanceof LightChannel.SetLight set) {
                requireOwnedLight(channel, set.light());
                requireLiveScene(set.scene());
            } else if (operation instanceof LightChannel.DropLight drop) {
                requireOwnedLight(channel, drop.light());
            }
        }
    }

    private static void applyLights(List<LightChannel.Operation> operations,
                                    Map<LightRef, LightValue> nextLights) {
        for (LightChannel.Operation operation : operations) {
            if (operation instanceof LightChannel.SetLight set) {
                nextLights.put((LightRef) set.light(),
                        new LightValue((SceneRef) set.scene(), set.descriptor()));
            } else if (operation instanceof LightChannel.DropLight drop) {
                nextLights.remove((LightRef) drop.light());
            }
        }
    }

    private static void applyGeometry(List<GeometryChannel.Operation> operations,
                                      Map<MeshRef, MeshValue> nextMeshes,
                                      Map<InstanceRef, InstanceValue> nextInstances) {
        for (GeometryChannel.Operation operation : operations) {
            if (operation instanceof GeometryChannel.SetMesh<?> set) {
                MeshRef mesh = (MeshRef) set.mesh();
                nextMeshes.put(mesh, new MeshValue(set.build()));
            } else if (operation instanceof GeometryChannel.DropMesh<?> drop) {
                MeshRef mesh = (MeshRef) drop.mesh();
                nextMeshes.remove(mesh);
                nextInstances.entrySet().removeIf(entry -> {
                    if (entry.getValue().mesh != mesh) return false;
                    return true;
                });
            } else if (operation instanceof GeometryChannel.SetInstance<?> set) {
                nextInstances.put((InstanceRef) set.instance(),
                        new InstanceValue((SceneRef) set.scene(), (MeshRef) set.mesh(), set));
            } else if (operation instanceof GeometryChannel.DropInstance drop) {
                nextInstances.remove((InstanceRef) drop.instance());
            }
        }
    }

    synchronized RetainedPublication selectEnvironment(SceneEnvironmentContributionChannel channel,
                                        EnvironmentBinding<?> binding) {
        requireEnvironmentChannel(channel);
        if (!channel.accepting) throw new IllegalStateException("environment channel is invalidated");
        Objects.requireNonNull(binding, "binding");
        SceneRef scene = requireLiveScene(channel.scene);
        programs.validateEnvironment(binding.implementation(), binding.bindingData());
        resources.validate(channel.owner, binding.bindingData().resource());
        LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue> selections =
                new LinkedHashMap<>(environmentSelections.getOrDefault(scene, new LinkedHashMap<>()));
        selections.remove(channel);
        EnvironmentValue selected = new EnvironmentValue(binding);
        selections.put(channel, selected);
        Map<SceneRef, EnvironmentValue> nextEnvironments = new LinkedHashMap<>(environments);
        nextEnvironments.put(scene, selected);
        RetainedSceneContentSnapshot next = contentSnapshot(revision + 1, lights, nextEnvironments);
        PublicationReceipt receipt = new PublicationReceipt(failures);
        PendingPublication publication = beginPublication(channel);
        try {
            backend.publishContent(next, () -> { receipt.publish(); completePublication(publication); });
        } catch (Throwable failure) {
            rejectPublication(publication);
            throw failure;
        }
        Map<SceneRef, LinkedHashMap<SceneEnvironmentContributionChannel, EnvironmentValue>> nextSelections =
                new LinkedHashMap<>(environmentSelections);
        nextSelections.put(scene, selections);
        environmentSelections = nextSelections;
        environments = nextEnvironments;
        revision++;
        return receipt;
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
        int previousMeshCount = nextMeshes.size();
        int previousInstanceCount = nextInstances.size();
        Set<MeshRef> ownedMeshes = new LinkedHashSet<>();
        nextMeshes.entrySet().removeIf(entry -> {
            if (entry.getKey().owner != channel) return false;
            ownedMeshes.add(entry.getKey()); return true;
        });
        nextInstances.entrySet().removeIf(entry -> entry.getKey().owner == channel
                || ownedMeshes.contains(entry.getValue().mesh));
        if (nextMeshes.size() == previousMeshCount && nextInstances.size() == previousInstanceCount) return;
        RetainedSceneSnapshot next = snapshot(revision + 1, nextMeshes, nextInstances, lights);
        PendingPublication publication = beginPublication(channel);
        try {
            backend.publish(next, () -> completePublication(publication));
        } catch (Throwable failure) {
            rejectPublication(publication);
            throw failure;
        }
        meshes = nextMeshes; instances = nextInstances; revision++;
    }

    synchronized void invalidate(LightContributionChannel channel) {
        quiesce(channel);
        channel.acceptingSubmissions = false;
        Map<LightRef, LightValue> nextLights = new LinkedHashMap<>(lights);
        int previousLightCount = nextLights.size();
        nextLights.entrySet().removeIf(entry -> entry.getKey().owner == channel);
        if (nextLights.size() == previousLightCount) return;
        RetainedSceneContentSnapshot next = contentSnapshot(revision + 1, nextLights, environments);
        PendingPublication publication = beginPublication(channel);
        try {
            backend.publishContent(next, () -> completePublication(publication));
        } catch (Throwable failure) {
            rejectPublication(publication);
            throw failure;
        }
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
            return;
        }
        Map<SceneRef, EnvironmentValue> nextEnvironments = new LinkedHashMap<>(environments);
        EnvironmentValue restored = lastValue(nextSceneSelections);
        if (restored == null) nextEnvironments.remove(scene);
        else nextEnvironments.put(scene, restored);
        RetainedSceneContentSnapshot next = contentSnapshot(revision + 1, lights, nextEnvironments);
        PendingPublication publication = beginPublication(channel);
        try {
            backend.publishContent(next, () -> completePublication(publication));
        } catch (Throwable failure) {
            rejectPublication(publication);
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

    /** Establishes the terminal no-more-frames boundary before owner retirement drains. */
    public void prepareForSessionClose() {
        backend.prepareForSessionClose();
        progress();
    }

    /** Makes frame-held resource leases eligible for an owner retirement drain. */
    public void settleFrameUses() {
        backend.settleFrameUses();
        progress();
        resources.progress();
    }

    private void drainOwner(Object owner) {
        while (true) {
            progress();
            synchronized (this) {
                if (publications.stream().noneMatch(publication -> publication.owners.contains(owner))) return;
                if (backendProgressAvailable) continue;
                awaitChange();
            }
        }
    }

    private synchronized PendingPublication beginPublication(Object... owners) {
        PendingPublication publication = new PendingPublication(Set.of(owners));
        publications.add(publication);
        return publication;
    }

    private synchronized void completePublication(PendingPublication publication) {
        publications.remove(publication);
        notifyAll();
    }

    private synchronized void rejectPublication(PendingPublication publication) {
        publications.remove(publication);
        notifyAll();
    }

    private synchronized void signalBackendProgress() {
        backendProgressAvailable = true;
        notifyAll();
    }

    private RetainedSceneGeometryDelta geometryDelta(
            long nextRevision, List<GeometryChannel.Operation> operations) {
        return geometryDelta(nextRevision, operations, List.of(), instances);
    }

    private RetainedSceneGeometryDelta geometryDelta(
            long nextRevision, List<GeometryChannel.Operation> operations,
            List<GeometryChannel.LatestInstance> latestInstances,
            Map<InstanceRef, InstanceValue> finalInstances) {
        List<RetainedSceneGeometryDelta.Mutation> mutations = new ArrayList<>();
        for (GeometryChannel.Operation operation : operations) {
            if (operation instanceof GeometryChannel.SetMesh<?> set) {
                MeshRef mesh = (MeshRef) set.mesh();
                mutations.add(new RetainedSceneGeometryDelta.SetMesh(
                        meshSnapshot(mesh, set.build())));
            } else if (operation instanceof GeometryChannel.DropMesh<?> drop) {
                mutations.add(new RetainedSceneGeometryDelta.DropMesh(
                        ((MeshRef<?>) drop.mesh()).identity));
            } else if (operation instanceof GeometryChannel.SetInstance<?> set) {
                mutations.add(new RetainedSceneGeometryDelta.SetInstance(
                        instanceSnapshot((InstanceRef) set.instance(), (SceneRef) set.scene(),
                                (MeshRef) set.mesh(), set)));
            } else if (operation instanceof GeometryChannel.DropInstance drop) {
                mutations.add(new RetainedSceneGeometryDelta.DropInstance(
                        ((InstanceRef) drop.instance()).identity));
            }
        }
        for (GeometryChannel.LatestInstance latest : latestInstances) {
            InstanceRef instance = (InstanceRef) latest.instance();
            InstanceValue value = finalInstances.get(instance);
            mutations.add(new RetainedSceneGeometryDelta.SetInstance(
                    instanceSnapshot(instance, value.scene, value.mesh, value.operation)));
        }
        return new RetainedSceneGeometryDelta(nextRevision, mutations);
    }

    private RetainedSceneSnapshot.Mesh meshSnapshot(MeshRef mesh, MeshBuild<?> build) {
        return new RetainedSceneSnapshot.Mesh(mesh.identity, build,
                build.geometries().stream().map(geometry ->
                        new RetainedSceneSnapshot.GeometryPrograms(
                                geometry.surface() == null
                                        ? 0
                                        : programs.resolve(geometry.surface().surface()),
                                geometry.volume() == null
                                        ? 0
                                        : programs.resolve(geometry.volume().volume())))
                        .toList());
    }

    private RetainedSceneSnapshot.Instance instanceSnapshot(
            InstanceRef instance, SceneRef scene, MeshRef mesh, GeometryChannel.SetInstance<?> set) {
        return new RetainedSceneSnapshot.Instance(instance.identity, scene, mesh.identity,
                set.transform(), set.mask(), set.instanceData(),
                set.primitiveLights().ranges().stream().map(range ->
                        new RetainedSceneSnapshot.PrimitiveEmitter(range.firstPrimitive(),
                                range.primitiveCount(), ((LightRef) range.light()).identity)).toList());
    }

    private void validateGeometry(GeometryContributionChannel channel, List<GeometryChannel.Operation> operations) {
        Map<MeshRef, MeshBuild<?>> changedMeshes = new LinkedHashMap<>();
        Set<MeshRef> droppedMeshes = new LinkedHashSet<>();
        for (GeometryChannel.Operation operation : operations) {
            if (operation instanceof GeometryChannel.SetMesh<?> set) {
                MeshRef<?> mesh = requireOwnedMesh(channel, set.mesh());
                validateBuild(channel, mesh, set.build());
                droppedMeshes.remove(mesh);
                changedMeshes.put(mesh, set.build());
            } else if (operation instanceof GeometryChannel.DropMesh<?> drop) {
                MeshRef<?> mesh = requireOwnedMesh(channel, drop.mesh());
                changedMeshes.remove(mesh);
                droppedMeshes.add(mesh);
            } else if (operation instanceof GeometryChannel.SetInstance<?> set) {
                requireOwnedInstance(channel, set.instance());
                requireLiveScene(set.scene());
                MeshRef<?> mesh = requireOwnedMesh(channel, set.mesh());
                MeshBuild<?> build = changedMeshes.containsKey(mesh)
                        ? changedMeshes.get(mesh)
                        : droppedMeshes.contains(mesh) ? null
                        : meshes.containsKey(mesh) ? meshes.get(mesh).build : null;
                if (build == null) throw new IllegalArgumentException("instance names an absent mesh");
                mesh.instanceType.require(set.instanceData());
                resources.validate(channel.owner, set.instanceData().resource());
                set.primitiveLights().ranges().forEach(range -> requireLightSelection(range.light()));
                validatePrimitiveLights(build, set.primitiveLights());
            } else if (operation instanceof GeometryChannel.DropInstance drop) {
                requireOwnedInstance(channel, drop.instance());
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

    private void validateBuild(GeometryContributionChannel channel, MeshRef<?> mesh, MeshBuild<?> build) {
        ContributionOwner owner = channel.owner;
        resources.validate(owner, build.positions().resource());
        resources.validate(owner, build.indices().resource());
        for (MeshBuild.Geometry<?> geometry : build.geometries()) {
            if (geometry.surface() != null) {
                programs.validateSurface(geometry.surface().surface(),
                        geometry.surface().bindingData(), mesh.instanceType,
                        !(geometry.surface().coverage() instanceof MeshBuild.CoveragePolicy.Opaque));
                resources.validate(owner, geometry.surface().bindingData().resource());
            }
            if (geometry.volume() != null) {
                programs.validateVolume(geometry.volume().volume(),
                        geometry.volume().bindingData(), mesh.instanceType);
                resources.validate(owner, geometry.volume().bindingData().resource());
            }
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
                                                        ? 0
                                                        : programs.resolve(geometry.surface().surface()),
                                                geometry.volume() == null
                                                        ? 0
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

    private RetainedSceneContentSnapshot contentSnapshot(long revision,
                                                          Map<LightRef, LightValue> lightValues,
                                                          Map<SceneRef, EnvironmentValue> environmentValues) {
        return new RetainedSceneContentSnapshot(revision,
                scenes.keySet().stream().map(scene -> new RetainedSceneSnapshot.Scene(
                        scene, environmentValues.containsKey(scene)
                                ? environmentValues.get(scene).binding : null)).toList(),
                lightValues.entrySet().stream().map(entry -> new RetainedSceneSnapshot.Light(
                        entry.getKey().identity, entry.getValue().scene, entry.getValue().descriptor)).toList());
    }

    private void publish() {
        RetainedSceneSnapshot next = snapshot(revision + 1);
        backend.publish(next, () -> { });
        revision++;
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

    private static <K, V> V lastValue(LinkedHashMap<K, V> values) {
        V last = null;
        for (V value : values.values()) last = value;
        return last;
    }

    private static final class PublicationReceipt implements RetainedPublication {
        private final SceneRetirementFailureHandler failures;
        private final List<Runnable> callbacks = new ArrayList<>();
        private volatile boolean visible;

        PublicationReceipt(SceneRetirementFailureHandler failures) {
            this.failures = failures;
        }

        @Override public boolean isVisible() { return visible; }

        @Override public void whenVisible(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            synchronized (this) {
                if (!visible) {
                    callbacks.add(callback);
                    return;
                }
            }
            invoke(callback);
        }

        void publish() {
            List<Runnable> ready;
            synchronized (this) {
                if (visible) return;
                visible = true;
                ready = List.copyOf(callbacks);
                callbacks.clear();
            }
            ready.forEach(this::invoke);
        }

        private void invoke(Runnable callback) {
            try {
                callback.run();
            } catch (Throwable failure) {
                failures.report(failure);
            }
        }
    }
    private record PendingPublication(Set<Object> owners) { }
    private static final class MeshValue { final MeshBuild<?> build; MeshValue(MeshBuild<?> b) { build=b; } }
    private static final class InstanceValue {
        final SceneRef scene; final MeshRef mesh; final GeometryChannel.SetInstance<?> operation;
        InstanceValue(SceneRef s, MeshRef m, GeometryChannel.SetInstance<?> o) { scene=s;mesh=m;operation=o; }
    }
    private static final class LightValue {
        final SceneRef scene; final dev.comfyfluffy.caustica.api.light.LightDescriptor descriptor;
        LightValue(SceneRef s, dev.comfyfluffy.caustica.api.light.LightDescriptor d) { scene=s;descriptor=d; }
    }
    private static final class EnvironmentValue {
        final EnvironmentBinding<?> binding;
        EnvironmentValue(EnvironmentBinding<?> binding) {
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
