package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;
import dev.comfyfluffy.caustica.api.session.RenderSessionFactory;
import dev.comfyfluffy.caustica.api.session.RenderSessionRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EngineRenderSessionChannel implements RenderSessionChannel, AutoCloseable {
    private final List<Registration> registrations = new ArrayList<>();
    private final List<EngineRenderSession> liveSessions = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized RenderSessionRegistration add(RenderSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        if (closed) throw new IllegalStateException("render-session registration is closed");
        Registration registration = new Registration(this, factory);
        registrations.add(registration);
        liveSessions.forEach(EngineRenderSession::requestReconcile);
        return registration;
    }

    synchronized EngineRenderSession openSession(ContributionScopeFactory scopes,
                                                  SessionFailureHandler failures) {
        if (closed) throw new IllegalStateException("render-session host is closed");
        EngineRenderSession session = new EngineRenderSession(this, scopes, failures);
        liveSessions.add(session);
        session.requestReconcile();
        return session;
    }

    synchronized List<Registration> snapshot() {
        return List.copyOf(registrations);
    }

    synchronized void remove(Registration registration) {
        if (!registrations.remove(registration)) return;
        liveSessions.forEach(EngineRenderSession::requestReconcile);
    }

    synchronized void detach(EngineRenderSession session) {
        liveSessions.remove(session);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        registrations.clear();
        liveSessions.forEach(EngineRenderSession::requestReconcile);
    }

    static final class Registration implements RenderSessionRegistration {
        private final EngineRenderSessionChannel channel;
        private final RenderSessionFactory factory;

        private Registration(EngineRenderSessionChannel channel, RenderSessionFactory factory) {
            this.channel = channel;
            this.factory = factory;
        }

        RenderSessionFactory factory() {
            return factory;
        }

        @Override
        public void close() {
            channel.remove(this);
        }
    }
}
