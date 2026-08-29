package dev.comfyfluffy.caustica.engine.pass;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFactory;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Deterministic owner-scoped pass registry and renderer dispatch controller for one session. */
public final class PassSession implements AutoCloseable {
    private final PassSchedulerBackend backend;
    private final PassFailureHandler failures;
    private final List<Registration<?>> registrations = new ArrayList<>();
    private final ArrayDeque<Registration<?>> closeQueue = new ArrayDeque<>();
    private long nextSequence;
    private boolean accepting = true;

    public PassSession(PassSchedulerBackend backend, PassFailureHandler failures) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public synchronized PassContributionChannel openChannel(Object owner) {
        Objects.requireNonNull(owner, "owner");
        if (!accepting) {
            throw new IllegalStateException("pass session is closed");
        }
        return new PassContributionChannel(this, owner);
    }

    PassRegistration addWorldResource(
            PassContributionChannel channel, PassFactory<WorldResourceSetup, PassFrame> factory) {
        return add(channel, PassKey.Stage.WORLD_RESOURCE, factory, backend.worldResourceSetup());
    }

    PassRegistration addPostEffect(
            PassContributionChannel channel, PassId id, PassPlacement placement,
            PassFactory<PostEffectSetup, PostEffectFrame> factory) {
        return add(channel, PassKey.Stage.POST_EFFECT, id, placement, factory, backend.postEffectSetup());
    }

    PassRegistration addUi(
            PassContributionChannel channel, PassId id, PassPlacement placement,
            PassFactory<UiSetup, UiFrame> factory) {
        return add(channel, PassKey.Stage.UI, id, placement, factory, backend.uiSetup());
    }

    private <S extends dev.comfyfluffy.caustica.api.pass.PassSetup, F extends PassFrame> PassRegistration add(
            PassContributionChannel channel, PassKey.Stage stage, PassFactory<S, F> factory, S setup) {
        return add(channel, stage, null, null, factory, setup);
    }

    private <S extends dev.comfyfluffy.caustica.api.pass.PassSetup, F extends PassFrame> PassRegistration add(
            PassContributionChannel channel, PassKey.Stage stage, PassId id, PassPlacement placement,
            PassFactory<S, F> factory, S setup) {
        Objects.requireNonNull(factory, "factory");
        if (stage != PassKey.Stage.WORLD_RESOURCE) {
            Objects.requireNonNull(id, "id");
            if (placement != null && placement.anchor().equals(id)) {
                throw new IllegalArgumentException("pass " + id + " cannot be ordered relative to itself");
            }
        }
        synchronized (this) {
            requireAccepting(channel);
        }
        Pass<F> pass = Objects.requireNonNull(factory.create(setup), "factory returned null pass");
        try {
            synchronized (this) {
                if (!accepting || !channel.accepting) {
                    throw new IllegalStateException("pass channel stopped during factory creation");
                }
                Registration<F> registration = new Registration<>(
                        this, channel, new PassKey(nextSequence, stage), id, placement, pass);
                if (stage != PassKey.Stage.WORLD_RESOURCE) {
                    ordered(stage, registration);
                }
                nextSequence++;
                registrations.add(registration);
                return registration;
            }
        } catch (RuntimeException | Error rejection) {
            try {
                pass.close();
            } catch (Throwable closeFailure) {
                rejection.addSuppressed(closeFailure);
            }
            throw rejection;
        }
    }

    /** Records all active world-resource passes in global acceptance order. */
    public void recordWorldResources() {
        dispatch(PassKey.Stage.WORLD_RESOURCE);
    }

    /** Records and composes all active post effects in constrained order. */
    public void recordPostEffects() {
        dispatch(PassKey.Stage.POST_EFFECT);
    }

    /** Records all active UI passes in constrained order. */
    public void recordUi() {
        dispatch(PassKey.Stage.UI);
    }

    private void dispatch(PassKey.Stage stage) {
        List<Registration<?>> ordered;
        synchronized (this) {
            ordered = stage == PassKey.Stage.WORLD_RESOURCE
                    ? registrations.stream().filter(registration -> registration.key.stage() == stage).toList()
                    : ordered(stage, null);
        }
        for (Registration<?> registration : ordered) {
            dispatchOne(registration);
        }
        progress();
    }

    private List<Registration<?>> ordered(PassKey.Stage stage, Registration<?> candidate) {
        List<Registration<?>> nodes = new ArrayList<>();
        for (Registration<?> registration : registrations) {
            if (registration.recording && registration.key.stage() == stage) {
                nodes.add(registration);
            }
        }
        if (candidate != null) nodes.add(candidate);

        Map<PassId, Registration<?>> byId = new HashMap<>();
        for (Registration<?> registration : nodes) {
            Registration<?> duplicate = byId.putIfAbsent(registration.id, registration);
            if (duplicate != null) {
                throw new IllegalStateException("duplicate live " + stageName(stage) + " pass id " + registration.id);
            }
        }

        Map<Registration<?>, List<Registration<?>>> outgoing = new HashMap<>();
        Map<Registration<?>, Integer> incoming = new HashMap<>();
        for (Registration<?> registration : nodes) {
            outgoing.put(registration, new ArrayList<>());
            incoming.put(registration, 0);
        }
        for (Registration<?> registration : nodes) {
            if (registration.placement == null) continue;
            Registration<?> anchor = byId.get(registration.placement.anchor());
            if (anchor == null) continue;
            Registration<?> before = registration.placement instanceof PassPlacement.Before
                    ? registration : anchor;
            Registration<?> after = before == registration ? anchor : registration;
            outgoing.get(before).add(after);
            incoming.put(after, incoming.get(after) + 1);
        }

        PriorityQueue<Registration<?>> ready = new PriorityQueue<>(
                Comparator.comparingLong(registration -> registration.key.sequence()));
        for (Registration<?> registration : nodes) {
            if (incoming.get(registration) == 0) ready.add(registration);
        }
        List<Registration<?>> result = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            Registration<?> registration = ready.remove();
            result.add(registration);
            for (Registration<?> dependent : outgoing.get(registration)) {
                int remaining = incoming.compute(dependent, (ignored, count) -> count - 1);
                if (remaining == 0) ready.add(dependent);
            }
        }
        if (result.size() != nodes.size()) {
            String ids = nodes.stream()
                    .filter(registration -> incoming.get(registration) != 0)
                    .map(registration -> registration.id)
                    .sorted()
                    .map(PassId::toString)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("unknown");
            throw new IllegalArgumentException(stageName(stage) + " pass ordering cycle: " + ids);
        }
        return List.copyOf(result);
    }

    private static String stageName(PassKey.Stage stage) {
        return switch (stage) {
            case WORLD_RESOURCE -> "world-resource";
            case POST_EFFECT -> "post-effect";
            case UI -> "ui";
        };
    }

    @SuppressWarnings("unchecked")
    private <F extends PassFrame> void dispatchOne(Registration<?> untyped) {
        Registration<F> registration = (Registration<F>) untyped;
        synchronized (this) {
            if (!registration.recording) {
                return;
            }
            registration.callbacks++;
            registration.uses++;
        }

        PassSchedulerBackend.Invocation<F> invocation;
        try {
            invocation = (PassSchedulerBackend.Invocation<F>) switch (registration.key.stage()) {
                case WORLD_RESOURCE -> backend.beginWorldResource(registration.key);
                case POST_EFFECT -> backend.beginPostEffect(registration.key);
                case UI -> backend.beginUi(registration.key);
            };
        } catch (Throwable failure) {
            synchronized (this) {
                registration.callbacks--;
                registration.uses--;
                disable(registration);
            }
            report(registration.key, failure);
            return;
        }

        boolean handedToBackend = false;
        try {
            registration.pass.record(invocation.frame());
            if (invocation instanceof PassSchedulerBackend.PostInvocation post) {
                post.validateOutputChain();
            }
            invocation.submit(() -> useDrained(registration));
            handedToBackend = true;
        } catch (Throwable failure) {
            synchronized (this) {
                disable(registration);
            }
            report(registration.key, failure);
            try {
                invocation.abandon(failure, () -> useDrained(registration));
                handedToBackend = true;
            } catch (Throwable abandonFailure) {
                report(registration.key, abandonFailure);
            }
        } finally {
            synchronized (this) {
                registration.callbacks--;
                if (!handedToBackend) {
                    registration.uses--;
                }
                scheduleCloseIfReady(registration);
                notifyAll();
            }
        }
    }

    private synchronized void useDrained(Registration<?> registration) {
        registration.uses--;
        scheduleCloseIfReady(registration);
        notifyAll();
    }

    synchronized void quiesce(PassContributionChannel channel) {
        channel.accepting = false;
        registrations.stream()
                .filter(registration -> registration.channel == channel)
                .forEach(registration -> registration.recording = false);
        while (registrations.stream().anyMatch(
                registration -> registration.channel == channel && registration.callbacks != 0)) {
            awaitChange();
        }
    }

    synchronized void invalidate(PassContributionChannel channel) {
        channel.accepting = false;
        registrations.stream()
                .filter(registration -> registration.channel == channel)
                .forEach(this::disable);
    }

    void drain(PassContributionChannel channel) {
        while (true) {
            progress();
            synchronized (this) {
                if (registrations.stream().noneMatch(registration -> registration.channel == channel)) {
                    return;
                }
                if (!closeQueue.isEmpty()) continue;
                awaitChange();
            }
        }
    }

    /** Runs eligible pass closes on the calling renderer thread. */
    public void progress() {
        while (true) {
            Registration<?> registration;
            synchronized (this) {
                registration = closeQueue.poll();
                if (registration == null) {
                    return;
                }
            }
            try {
                registration.pass.close();
            } catch (Throwable failure) {
                report(registration.key, failure);
            }
            synchronized (this) {
                registration.closed = true;
                registrations.remove(registration);
                notifyAll();
            }
        }
    }

    /** Quiesces, invalidates, drains, and closes every pass registration in the session. */
    @Override
    public void close() {
        List<PassContributionChannel> channels;
        synchronized (this) {
            if (!accepting) {
                return;
            }
            accepting = false;
            channels = registrations.stream().map(registration -> registration.channel).distinct().toList();
        }
        channels.forEach(this::quiesce);
        channels.forEach(this::invalidate);
        channels.forEach(this::drain);
    }

    private synchronized void close(Registration<?> registration) {
        disable(registration);
    }

    private void disable(Registration<?> registration) {
        registration.recording = false;
        registration.closeRequested = true;
        scheduleCloseIfReady(registration);
    }

    private void scheduleCloseIfReady(Registration<?> registration) {
        if (registration.closeRequested && registration.callbacks == 0 && registration.uses == 0
                && !registration.closeScheduled && !registration.closed) {
            registration.closeScheduled = true;
            closeQueue.add(registration);
            notifyAll();
        }
    }

    private void requireAccepting(PassContributionChannel channel) {
        if (!accepting || !channel.accepting) {
            throw new IllegalStateException("pass channel is stopped");
        }
    }

    private void awaitChange() {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining passes", interrupted);
        }
    }

    private void report(PassKey key, Throwable failure) {
        try {
            failures.failed(key, failure);
        } catch (Throwable ignored) {
            // Failure reporting must not break renderer lifecycle progress.
        }
    }

    private static final class Registration<F extends PassFrame> implements PassRegistration {
        private final PassSession session;
        private final PassContributionChannel channel;
        private final PassKey key;
        private final PassId id;
        private final PassPlacement placement;
        private final Pass<F> pass;
        private boolean recording = true;
        private boolean closeRequested;
        private boolean closeScheduled;
        private boolean closed;
        private int callbacks;
        private int uses;

        private Registration(
                PassSession session, PassContributionChannel channel, PassKey key,
                PassId id, PassPlacement placement, Pass<F> pass) {
            this.session = session;
            this.channel = channel;
            this.key = key;
            this.id = id;
            this.placement = placement;
            this.pass = pass;
        }

        @Override
        public void close() {
            session.close(this);
        }
    }
}
