package dev.comfyfluffy.caustica.minecraft.adapter.session;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.session.EnvironmentSelectionScope;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionChannel;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionRegistration;
import dev.comfyfluffy.caustica.engine.session.ContributionScopeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Process-scoped Minecraft registration and creation of host-owned world-epoch controllers. */
public final class MinecraftWorldSessionHost implements AutoCloseable {
    private final Channel channel = new Channel();
    private final MinecraftApi api;

    public MinecraftWorldSessionHost() {
        api = new MinecraftApi(channel);
    }

    public MinecraftApi api() { return api; }

    /** The environment factory creates a fresh selection scope for each contribution in the borrowed scene. */
    public MinecraftWorldSession openSession(
            ContributionScopeFactory scopes,
            Function<SceneId, EnvironmentSelectionScope> environments,
            SceneId scene,
            MinecraftDimensionKey dimension,
            ResourcePackEpoch resourcePackEpoch,
            MinecraftSessionFailureHandler failures) {
        return channel.open(scopes, environments, scene, dimension, resourcePackEpoch, failures);
    }

    @Override public void close() { channel.close(); }

    static final class Channel implements MinecraftWorldSessionChannel {
        private final List<Registration> registrations = new ArrayList<>();
        private final List<MinecraftWorldSession> sessions = new ArrayList<>();
        private boolean accepting = true;

        @Override
        public MinecraftWorldSessionRegistration add(MinecraftWorldSessionFactory factory) {
            Objects.requireNonNull(factory, "factory");
            Registration registration;
            List<MinecraftWorldSession> live;
            synchronized (this) {
                if (!accepting) throw new IllegalStateException("Minecraft session host is closed");
                registration = new Registration(this, factory);
                registrations.add(registration);
                live = List.copyOf(sessions);
            }
            live.forEach(MinecraftWorldSession::requestReconcile);
            return registration;
        }

        synchronized List<Registration> snapshot() { return List.copyOf(registrations); }

        MinecraftWorldSession open(
                ContributionScopeFactory scopes,
                Function<SceneId, EnvironmentSelectionScope> environments,
                SceneId scene,
                MinecraftDimensionKey dimension,
                ResourcePackEpoch resourcePackEpoch,
                MinecraftSessionFailureHandler failures) {
            MinecraftWorldSession session;
            synchronized (this) {
                if (!accepting) throw new IllegalStateException("Minecraft session host is closed");
                session = new MinecraftWorldSession(this, scopes, environments, scene, dimension,
                        resourcePackEpoch, failures);
                sessions.add(session);
            }
            session.requestReconcile();
            return session;
        }

        synchronized void detach(MinecraftWorldSession session) { sessions.remove(session); }

        void remove(Registration registration) {
            List<MinecraftWorldSession> live;
            synchronized (this) {
                if (!registrations.remove(registration)) return;
                live = List.copyOf(sessions);
            }
            live.forEach(MinecraftWorldSession::requestReconcile);
        }

        synchronized void close() {
            if (!accepting) return;
            accepting = false;
            registrations.clear();
        }

        static final class Registration implements MinecraftWorldSessionRegistration {
            private final Channel channel;
            private final MinecraftWorldSessionFactory factory;

            Registration(Channel channel, MinecraftWorldSessionFactory factory) {
                this.channel = channel;
                this.factory = factory;
            }

            MinecraftWorldSessionFactory factory() { return factory; }

            @Override public void close() { channel.remove(this); }
        }
    }
}
