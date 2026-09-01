package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.engine.session.ContributionScope;
import dev.comfyfluffy.caustica.engine.session.EngineRenderSession;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftEnvironmentScope;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSession;
import dev.comfyfluffy.caustica.minecraft.adapter.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.config.CausticaOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftApiBootstrapTest {
    @TempDir Path temporaryDirectory;
    @Test
    void registersMinecraftOnlyDiscoveryAndDeduplicatesDualCapabilityInstances() {
        SettingsRegistry settings = new SettingsRegistry();
        OptionLookup options = id -> { throw new AssertionError(id); };
        RenderSessionHost renderHost = new RenderSessionHost(options);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(options);
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
        MinecraftApiBootstrap.registerExtensions(renderHost, minecraftHost, generic);
        MinecraftApiBootstrap.registerMinecraftExtensions(
                minecraftHost, List.of(new MinecraftOnly(), dual), generic);

        MinecraftWorldSession session = minecraftHost.openSession(
                owner -> new EmptyScope(), (owner, scene) -> new MinecraftEnvironmentScope() {
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
        SettingsRegistry settings = new SettingsRegistry();
        OptionLookup options = id -> { throw new AssertionError(id); };
        RenderSessionHost host = new RenderSessionHost(options);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(options);
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

        List<CausticaExtension> generic = List.of(new SessionFails(), new SettingsFails());
        MinecraftApiBootstrap.registerSettings(settings, generic, List.of());
        MinecraftApiBootstrap.registerExtensions(host, minecraftHost, generic);
        EngineRenderSession session = host.openSession(owner -> new EmptyScope(), failure -> {
            throw new AssertionError(failure);
        });
        session.processPendingChanges();
        MinecraftWorldSession minecraftSession = minecraftHost.openSession(
                owner -> new EmptyScope(), (owner, scene) -> new MinecraftEnvironmentScope() {
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

    @Test
    void settingsAreDeclaredBeforeEitherProcessApiIsPublishedToExtensions() {
        SettingsRegistry settings = new SettingsRegistry();
        ResourceId feature = ResourceId.of("test", "ordered");
        Option<Boolean> enabled = Option.bool("enabled", true);
        boolean[] genericSawOptions = {false};
        boolean[] minecraftSawOptions = {false};
        class Ordered implements CausticaExtension, MinecraftExtension, CausticaSettingsExtension {
            @Override public void registerSettings(SettingsRegistry registry) {
                registry.feature(feature).option(enabled).register();
            }
            @Override public void register(CausticaApi api) {
                genericSawOptions[0] = api.options().options(feature).get(enabled);
            }
            @Override public void registerMinecraft(MinecraftApi api) {
                minecraftSawOptions[0] = api.options().options(feature).get(enabled);
            }
        }
        Ordered extension = new Ordered();
        List<CausticaExtension> generic = List.of(extension);
        List<MinecraftExtension> minecraft = List.of(extension);
        MinecraftApiBootstrap.registerSettings(settings, generic, minecraft);
        OptionLookup options = CausticaOptions.load(temporaryDirectory.resolve("options.toml"), settings);
        RenderSessionHost host = new RenderSessionHost(options);
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost(options);

        MinecraftApiBootstrap.registerExtensions(host, minecraftHost, generic);
        MinecraftApiBootstrap.registerMinecraftExtensions(minecraftHost, minecraft, generic);

        assertTrue(genericSawOptions[0]);
        assertTrue(minecraftSawOptions[0]);
    }

    private static final class EmptyScope implements ContributionScope {
        @Override public GpuDevice gpu() { return null; }
        @Override public ProgramChannel program() { return null; }
        @Override public PassChannel passes() { return null; }
        @Override public GeometryChannel geometry() { return null; }
        @Override public LightChannel lights() { return null; }
        @Override public dev.comfyfluffy.caustica.api.resource.ResourceChannel resources() { return null; }
        @Override public void quiesce() { }
        @Override public void invalidate() { }
        @Override public void drain() { }
        @Override public void close() { }
    }
}
