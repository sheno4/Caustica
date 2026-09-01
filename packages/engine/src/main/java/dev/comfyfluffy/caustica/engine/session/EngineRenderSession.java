package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.api.resource.ResourceChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session-control-thread orchestrator for the process registrations active in one render session. */
public final class EngineRenderSession implements AutoCloseable {
    private final EngineRenderSessionChannel channel;
    private final ContributionScopeFactory scopes;
    private final SessionFailureHandler failures;
    private final Map<EngineRenderSessionChannel.Registration, ActiveContribution> active =
            new LinkedHashMap<>();
    private List<ActiveContribution> closing = List.of();
    private long nextOwnerSequence;
    private volatile boolean reconcileRequested;
    private boolean closed;

    EngineRenderSession(EngineRenderSessionChannel channel, ContributionScopeFactory scopes,
                        SessionFailureHandler failures) {
        this.channel = channel;
        this.scopes = scopes;
        this.failures = failures;
    }

    void requestReconcile() {
        reconcileRequested = true;
    }

    /**
     * Applies queued factory additions and removals on the caller's session-control thread.
     * New contributions open in process-registration order; removals fully drain before this method returns.
     */
    public void processPendingChanges() {
        requireOpen();
        if (!reconcileRequested) return;
        reconcileRequested = false;
        List<EngineRenderSessionChannel.Registration> desired = channel.snapshot();

        List<ActiveContribution> removed = new ArrayList<>();
        active.entrySet().removeIf(entry -> {
            if (desired.contains(entry.getKey())) return false;
            removed.add(entry.getValue());
            return true;
        });
        teardown(removed, true);

        for (EngineRenderSessionChannel.Registration registration : desired) {
            if (!active.containsKey(registration)) {
                open(registration);
            }
        }
        if (reconcileRequested) processPendingChanges();
    }

    /** Number of successfully opened contributions currently owned by this session. */
    public int contributionCount() {
        return active.size();
    }

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

    private void open(EngineRenderSessionChannel.Registration registration) {
        ContributionOwner owner = new ContributionOwner(++nextOwnerSequence);
        ContributionScope scope;
        try {
            scope = java.util.Objects.requireNonNull(scopes.create(owner), "scope factory returned null");
        } catch (Throwable failure) {
            report(owner, SessionFailure.Stage.CREATE_SCOPE, failure);
            return;
        }

        RenderSessionContribution contribution;
        try {
            contribution = java.util.Objects.requireNonNull(
                    registration.factory().open(new Context(scope)), "session factory returned null");
        } catch (Throwable failure) {
            teardown(List.of(new ActiveContribution(owner, scope, null)), false);
            report(owner, SessionFailure.Stage.OPEN_CONTRIBUTION, failure);
            return;
        }
        active.put(registration, new ActiveContribution(owner, scope, contribution));
    }

    private void teardown(List<ActiveContribution> contributions, boolean invokeContributionHooks) {
        quiesceAndInvalidate(contributions, invokeContributionHooks);
        drainAndClose(contributions, invokeContributionHooks);
    }

    private void quiesceAndInvalidate(List<ActiveContribution> contributions,
                                      boolean invokeContributionHooks) {
        for (ActiveContribution contribution : contributions) {
            invoke(contribution, SessionFailure.Stage.QUIESCE, contribution.scope::quiesce);
        }
        if (invokeContributionHooks) {
            for (ActiveContribution contribution : contributions) {
                invoke(contribution, SessionFailure.Stage.STOP_CONTRIBUTION, contribution.contribution::stop);
            }
        }
        for (ActiveContribution contribution : contributions) {
            invoke(contribution, SessionFailure.Stage.INVALIDATE, contribution.scope::invalidate);
        }
    }

    private void drainAndClose(List<ActiveContribution> contributions,
                               boolean invokeContributionHooks) {
        for (ActiveContribution contribution : contributions) {
            invoke(contribution, SessionFailure.Stage.DRAIN, contribution.scope::drain);
        }
        if (invokeContributionHooks) {
            for (ActiveContribution contribution : contributions) {
                invoke(contribution, SessionFailure.Stage.CLOSE_CONTRIBUTION, contribution.contribution::close);
            }
        }
        for (ActiveContribution contribution : contributions) {
            invoke(contribution, SessionFailure.Stage.CLOSE_SCOPE, contribution.scope::close);
        }
    }

    private void invoke(ActiveContribution contribution, SessionFailure.Stage stage, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            report(contribution.owner, stage, failure);
        }
    }

    private void report(ContributionOwner owner, SessionFailure.Stage stage, Throwable failure) {
        failures.report(new SessionFailure(owner, stage, failure));
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("render session is closed");
    }

    private record ActiveContribution(ContributionOwner owner, ContributionScope scope,
                                      RenderSessionContribution contribution) {
    }

    private record Context(ContributionScope scope) implements RenderSessionContext {
        @Override public GpuDevice gpu() { return scope.gpu(); }
        @Override public ProgramChannel program() { return scope.program(); }
        @Override public PassChannel passes() { return scope.passes(); }
        @Override public GeometryChannel geometry() { return scope.geometry(); }
        @Override public LightChannel lights() { return scope.lights(); }
        @Override public ResourceChannel resources() { return scope.resources(); }
    }
}
