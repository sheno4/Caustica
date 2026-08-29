package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.engine.session.ContributionScope;
import dev.comfyfluffy.caustica.engine.session.EngineRenderSession;
import dev.comfyfluffy.caustica.engine.session.EngineMinecraftWorldSession;
import dev.comfyfluffy.caustica.engine.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftApiBootstrapTest {
    @Test
    void registersMinecraftOnlyDiscoveryAndDeduplicatesDualCapabilityInstances() {
        RenderSessionHost renderHost = new RenderSessionHost();
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost();
        SettingsRegistry settings = new SettingsRegistry();
        class MinecraftOnly implements MinecraftExtension {
            @Override public void registerMinecraft(MinecraftApi api) {
                api.sessions().add(context -> MinecraftWorldSessionContribution.EMPTY);
            }
        }
        class Dual implements CausticaExtension, MinecraftExtension {
            @Override public void register(CausticaApi api) { }
            @Override public void registerMinecraft(MinecraftApi api) {
                api.sessions().add(context -> MinecraftWorldSessionContribution.EMPTY);
            }
        }
        Dual dual = new Dual();
        List<CausticaExtension> generic = List.of(dual);
        MinecraftApiBootstrap.registerExtensions(renderHost, minecraftHost, settings, generic);
        MinecraftApiBootstrap.registerMinecraftExtensions(
                minecraftHost, settings, List.of(new MinecraftOnly(), dual), generic);

        EngineMinecraftWorldSession session = minecraftHost.openSession(
                owner -> new EmptyScope(), (owner, scene) -> new dev.comfyfluffy.caustica.engine.session.MinecraftEnvironmentScope() {
                    @Override public void select(dev.comfyfluffy.caustica.api.scene.EnvironmentBinding<?> binding) { }
                    @Override public void invalidate() { }
                    @Override public void drain() { }
                }, new dev.comfyfluffy.caustica.api.scene.SceneId() { },
                MinecraftDimensionKey.of("minecraft", "overworld"), new ResourcePackEpoch(0),
                failure -> { throw new AssertionError(failure); });
        session.processPendingChanges();

        assertEquals(2, session.contributionCount());
        session.close();
    }

    @Test
    void sessionAndSettingsFailuresAreIsolatedForEachDiscoveredExtension() {
        RenderSessionHost host = new RenderSessionHost();
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost();
        SettingsRegistry settings = new SettingsRegistry();
        ResourceId settingsSurvived = ResourceId.of("test", "settings-survived");

        class SessionFails implements CausticaExtension, CausticaSettingsExtension {
            @Override public void register(CausticaApi api) { throw new IllegalStateException("session"); }
            @Override public void registerSettings(SettingsRegistry registry) {
                registry.feature(settingsSurvived).register();
            }
        }
        class SettingsFails implements CausticaExtension, MinecraftExtension, CausticaSettingsExtension {
            @Override public void register(CausticaApi api) {
                api.sessions().add(context -> RenderSessionContribution.EMPTY);
            }
            @Override public void registerSettings(SettingsRegistry registry) {
                throw new IllegalStateException("settings");
            }
            @Override public void registerMinecraft(MinecraftApi api) {
                api.sessions().add(context -> MinecraftWorldSessionContribution.EMPTY);
            }
        }

        MinecraftApiBootstrap.registerExtensions(
                host, minecraftHost, settings, List.of(new SessionFails(), new SettingsFails()));
        EngineRenderSession session = host.openSession(owner -> new EmptyScope(), failure -> {
            throw new AssertionError(failure);
        });
        session.processPendingChanges();
        EngineMinecraftWorldSession minecraftSession = minecraftHost.openSession(
                owner -> new EmptyScope(), (owner, scene) -> new dev.comfyfluffy.caustica.engine.session.MinecraftEnvironmentScope() {
                    @Override public void select(dev.comfyfluffy.caustica.api.scene.EnvironmentBinding<?> binding) { }
                    @Override public void invalidate() { }
                    @Override public void drain() { }
                }, new dev.comfyfluffy.caustica.api.scene.SceneId() { },
                MinecraftDimensionKey.of("minecraft", "overworld"), new ResourcePackEpoch(0),
                failure -> { throw new AssertionError(failure); });
        minecraftSession.processPendingChanges();

        assertTrue(settings.declared(settingsSurvived));
        assertEquals(1, session.contributionCount());
        assertEquals(1, minecraftSession.contributionCount());
        minecraftSession.close();
        session.close();
    }

    private static final class EmptyScope implements ContributionScope {
        @Override public GpuDevice gpu() { return null; }
        @Override public ProgramChannel program() { return null; }
        @Override public PassChannel passes() { return null; }
        @Override public GeometryChannel geometry() { return null; }
        @Override public LightChannel lights() { return null; }
        @Override public void quiesce() { }
        @Override public void invalidate() { }
        @Override public void drain() { }
        @Override public void close() { }
    }
}
