package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionChannel;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Process-scoped Minecraft registration and creation of host-owned world-epoch controllers. */
public final class MinecraftWorldSessionHost implements AutoCloseable {
    private final Channel channel = new Channel();
    private final MinecraftApi api = new MinecraftApi(channel);

    public MinecraftApi api() { return api; }

    public EngineMinecraftWorldSession openSession(
            ContributionScopeFactory scopes,
            MinecraftEnvironmentScopeFactory environments,
            dev.comfyfluffy.caustica.api.scene.SceneId scene,
            dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey dimension,
            dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch resourcePackEpoch,
            MinecraftSessionFailureHandler failures) {
        return channel.open(scopes, environments, scene, dimension, resourcePackEpoch, failures);
    }

    @Override public void close() { channel.close(); }

    static final class Channel implements MinecraftWorldSessionChannel {
        private final List<Registration> registrations = new ArrayList<>();
        private final List<EngineMinecraftWorldSession> sessions = new ArrayList<>();
        private boolean accepting = true;

        @Override
        public MinecraftWorldSessionRegistration add(MinecraftWorldSessionFactory factory) {
            Objects.requireNonNull(factory, "factory");
            Registration registration;
            List<EngineMinecraftWorldSession> live;
            synchronized (this) {
                if (!accepting) throw new IllegalStateException("Minecraft session host is closed");
                registration = new Registration(this, factory);
                registrations.add(registration);
                live = List.copyOf(sessions);
            }
            live.forEach(EngineMinecraftWorldSession::requestReconcile);
            return registration;
        }

        synchronized List<Registration> snapshot() { return List.copyOf(registrations); }

        EngineMinecraftWorldSession open(
                ContributionScopeFactory scopes,
                MinecraftEnvironmentScopeFactory environments,
                dev.comfyfluffy.caustica.api.scene.SceneId scene,
                dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey dimension,
                dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch resourcePackEpoch,
                MinecraftSessionFailureHandler failures) {
            EngineMinecraftWorldSession session;
            synchronized (this) {
                if (!accepting) throw new IllegalStateException("Minecraft session host is closed");
                session = new EngineMinecraftWorldSession(this, scopes, environments, scene, dimension,
                        resourcePackEpoch, failures);
                sessions.add(session);
            }
            session.requestReconcile();
            return session;
        }

        synchronized void detach(EngineMinecraftWorldSession session) { sessions.remove(session); }

        void remove(Registration registration) {
            List<EngineMinecraftWorldSession> live;
            synchronized (this) {
                if (!registrations.remove(registration)) return;
                live = List.copyOf(sessions);
            }
            live.forEach(EngineMinecraftWorldSession::requestReconcile);
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
