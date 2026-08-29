package dev.comfyfluffy.caustica.engine.pass;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFactory;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
            PassContributionChannel channel, PassFactory<PostEffectSetup, PostEffectFrame> factory) {
        return add(channel, PassKey.Stage.POST_EFFECT, factory, backend.postEffectSetup());
    }

    PassRegistration addUi(PassContributionChannel channel, PassFactory<UiSetup, UiFrame> factory) {
        return add(channel, PassKey.Stage.UI, factory, backend.uiSetup());
    }

    private <S extends dev.comfyfluffy.caustica.api.pass.PassSetup, F extends PassFrame> PassRegistration add(
            PassContributionChannel channel, PassKey.Stage stage, PassFactory<S, F> factory, S setup) {
        Objects.requireNonNull(factory, "factory");
        synchronized (this) {
            requireAccepting(channel);
        }
        Pass<F> pass = Objects.requireNonNull(factory.create(setup), "factory returned null pass");
        synchronized (this) {
            if (!accepting || !channel.accepting) {
                pass.close();
                throw new IllegalStateException("pass channel stopped during factory creation");
            }
            Registration<F> registration = new Registration<>(
                    this, channel, new PassKey(nextSequence++, stage), pass);
            registrations.add(registration);
            return registration;
        }
    }

    /** Records all active world-resource passes in global acceptance order. */
    public void recordWorldResources() {
        dispatch(PassKey.Stage.WORLD_RESOURCE);
    }

    /** Records and composes all active post effects in global acceptance order. */
    public void recordPostEffects() {
        dispatch(PassKey.Stage.POST_EFFECT);
    }

    /** Records all active UI passes in global acceptance order. */
    public void recordUi() {
        dispatch(PassKey.Stage.UI);
    }

    private void dispatch(PassKey.Stage stage) {
        List<Registration<?>> ordered;
        synchronized (this) {
            ordered = registrations.stream().filter(registration -> registration.key.stage() == stage).toList();
        }
        for (Registration<?> registration : ordered) {
            dispatchOne(registration);
        }
        progress();
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
        private final Pass<F> pass;
        private boolean recording = true;
        private boolean closeRequested;
        private boolean closeScheduled;
        private boolean closed;
        private int callbacks;
        private int uses;

        private Registration(
                PassSession session, PassContributionChannel channel, PassKey key, Pass<F> pass) {
            this.session = session;
            this.channel = channel;
            this.key = key;
            this.pass = pass;
        }

        @Override
        public void close() {
            session.close(this);
        }
    }
}
