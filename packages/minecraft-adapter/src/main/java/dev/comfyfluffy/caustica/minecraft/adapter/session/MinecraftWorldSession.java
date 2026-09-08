package dev.comfyfluffy.caustica.minecraft.adapter.session;

import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;
import dev.comfyfluffy.caustica.engine.session.EnvironmentSelectionScope;
import dev.comfyfluffy.caustica.engine.session.ContributionScope;
import dev.comfyfluffy.caustica.engine.session.ContributionScopeFactory;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Session-control-thread lifecycle for one host-owned Minecraft client-world/dimension epoch. */
public final class MinecraftWorldSession implements AutoCloseable {
    private final MinecraftWorldSessionHost.Channel channel;
    private final ContributionScopeFactory scopes;
    private final MinecraftEnvironmentScopeFactory environments;
    private final SceneId scene;
    private final MinecraftDimensionKey dimension;
    private final MinecraftSessionFailureHandler failures;
    private final Map<MinecraftWorldSessionHost.Channel.Registration, ActiveContribution> active =
            new LinkedHashMap<>();
    private List<ActiveContribution> closing = List.of();
    private final Set<MinecraftWorldSessionHost.Channel.Registration> attempted = new LinkedHashSet<>();
    private ResourcePackEpoch resourcePackEpoch;
    private long nextOwnerSequence;
    private volatile boolean reconcileRequested;
    private boolean closed;

    MinecraftWorldSession(
            MinecraftWorldSessionHost.Channel channel,
            ContributionScopeFactory scopes,
            MinecraftEnvironmentScopeFactory environments,
            SceneId scene,
            MinecraftDimensionKey dimension,
            ResourcePackEpoch resourcePackEpoch,
            MinecraftSessionFailureHandler failures) {
        this.channel = channel;
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.environments = Objects.requireNonNull(environments, "environments");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.resourcePackEpoch = Objects.requireNonNull(resourcePackEpoch, "resourcePackEpoch");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    void requestReconcile() { reconcileRequested = true; }

    /** Applies process registration changes at the host's world-session control boundary. */
    public void processPendingChanges() {
        requireOpen();
        while (reconcileRequested) {
            requireOpen();
            reconcileRequested = false;
            List<MinecraftWorldSessionHost.Channel.Registration> desired = channel.snapshot();
            attempted.retainAll(desired);
            List<ActiveContribution> removed = new ArrayList<>();
            active.entrySet().removeIf(entry -> {
                if (desired.contains(entry.getKey())) return false;
                removed.add(entry.getValue());
                return true;
            });
            teardown(removed, true);
            for (MinecraftWorldSessionHost.Channel.Registration registration : desired) {
                if (attempted.add(registration)) open(registration);
            }
        }
    }

    /** Delivers one newly applied resource-pack epoch in host generation order. */
    public void resourcePackChanged(ResourcePackEpoch epoch) {
        requireOpen();
        Objects.requireNonNull(epoch, "epoch");
        if (epoch.generation() <= resourcePackEpoch.generation()) {
            throw new IllegalArgumentException("resource-pack generation must increase");
        }
        resourcePackEpoch = epoch;
        for (ActiveContribution contribution : active.values()) {
            invoke(contribution, MinecraftSessionFailure.Stage.RESOURCE_PACK_CHANGED,
                    () -> contribution.contribution.resourcePackChanged(epoch));
        }
    }

    public int contributionCount() { return active.size(); }
    public ResourcePackEpoch resourcePackEpoch() { return resourcePackEpoch; }

    /**
     * Closes a standalone session whose supplied scopes can complete their own drain operations.
     * Engine-owned scopes are closed by {@link MinecraftEngineWorldSession}, which inserts the shared
     * retained-scene settlement boundary between invalidation and drain.
     */
    @Override
    public void close() {
        beginClose();
        finishClose();
    }

    void beginClose() {
        if (closed) return;
        closed = true;
        channel.detach(this);
        closing = new ArrayList<>(active.values());
        active.clear();
        quiesceAndInvalidate(closing, true);
    }

    void finishClose() {
        if (!closed) beginClose();
        drainAndClose(closing, true);
        closing = List.of();
    }

    private void open(MinecraftWorldSessionHost.Channel.Registration registration) {
        ContributionOwner owner = new ContributionOwner(++nextOwnerSequence);
        ContributionScope scope;
        try {
            scope = Objects.requireNonNull(scopes.create(owner), "scope factory returned null");
        } catch (Throwable failure) {
            report(owner, MinecraftSessionFailure.Stage.CREATE_SCOPE, failure);
            return;
        }
        EnvironmentSelectionScope environment;
        try {
            environment = Objects.requireNonNull(environments.create(owner, scene),
                    "environment scope factory returned null");
        } catch (Throwable failure) {
            teardown(List.of(new ActiveContribution(owner, scope, null, null)), false);
            report(owner, MinecraftSessionFailure.Stage.CREATE_SCOPE, failure);
            return;
        }
        MinecraftWorldSessionContribution contribution;
        try {
            contribution = Objects.requireNonNull(registration.factory().open(
                    new Context(new CoreContext(scope), scene, dimension, resourcePackEpoch, environment::select)),
                    "Minecraft world-session factory returned null");
        } catch (Throwable failure) {
            teardown(List.of(new ActiveContribution(owner, scope, environment, null)), false);
            report(owner, MinecraftSessionFailure.Stage.OPEN_CONTRIBUTION, failure);
            return;
        }
        active.put(registration, new ActiveContribution(owner, scope, environment, contribution));
    }

    private void teardown(List<ActiveContribution> contributions, boolean invokeContributionHooks) {
        quiesceAndInvalidate(contributions, invokeContributionHooks);
        drainAndClose(contributions, invokeContributionHooks);
    }

    private void quiesceAndInvalidate(List<ActiveContribution> contributions,
                                      boolean invokeContributionHooks) {
        for (ActiveContribution contribution : contributions)
            invoke(contribution, MinecraftSessionFailure.Stage.QUIESCE, contribution.scope::quiesce);
        if (invokeContributionHooks) {
            for (ActiveContribution contribution : contributions)
                invoke(contribution, MinecraftSessionFailure.Stage.STOP_CONTRIBUTION,
                        contribution.contribution::stop);
        }
        for (ActiveContribution contribution : contributions) {
            if (contribution.environment != null)
                invoke(contribution, MinecraftSessionFailure.Stage.INVALIDATE,
                        contribution.environment::invalidate);
        }
        for (ActiveContribution contribution : contributions)
            invoke(contribution, MinecraftSessionFailure.Stage.INVALIDATE, contribution.scope::invalidate);
    }

    private void drainAndClose(List<ActiveContribution> contributions,
                               boolean invokeContributionHooks) {
        for (ActiveContribution contribution : contributions) {
            if (contribution.environment != null)
                invoke(contribution, MinecraftSessionFailure.Stage.DRAIN, contribution.environment::drain);
        }
        for (ActiveContribution contribution : contributions)
            invoke(contribution, MinecraftSessionFailure.Stage.DRAIN, contribution.scope::drain);
        if (invokeContributionHooks) {
            for (ActiveContribution contribution : contributions)
                invoke(contribution, MinecraftSessionFailure.Stage.CLOSE_CONTRIBUTION,
                        contribution.contribution::close);
        }
        for (ActiveContribution contribution : contributions)
            invoke(contribution, MinecraftSessionFailure.Stage.CLOSE_SCOPE, contribution.scope::close);
    }

    private void invoke(ActiveContribution contribution, MinecraftSessionFailure.Stage stage, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) { report(contribution.owner, stage, failure); }
    }

    private void report(ContributionOwner owner, MinecraftSessionFailure.Stage stage, Throwable failure) {
        failures.report(new MinecraftSessionFailure(owner, stage, failure));
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Minecraft world session is closed");
    }

    private record ActiveContribution(ContributionOwner owner, ContributionScope scope,
                                      EnvironmentSelectionScope environment,
                                      MinecraftWorldSessionContribution contribution) { }

    private record Context(RenderSessionContext renderSession, SceneId scene,
                           MinecraftDimensionKey dimension, ResourcePackEpoch resourcePackEpoch,
                           MinecraftEnvironmentSelector environment)
            implements MinecraftWorldSessionContext { }

    private record CoreContext(ContributionScope scope) implements RenderSessionContext {
        @Override public GpuDevice gpu() { return scope.gpu(); }
        @Override public GpuComputeQueue compute() { return scope.compute(); }
        @Override public ProgramChannel program() { return scope.program(); }
        @Override public PassChannel passes() { return scope.passes(); }
        @Override public MeshPreparer meshes() { return scope.meshes(); }
        @Override public SceneChannel scene() { return scope.scene(); }
        @Override public dev.comfyfluffy.caustica.api.resource.ResourceFactory resources() {
            return scope.resources();
        }
    }
}
