package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.FeatureSettings;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Minecraft adapter declarations and host registration of the built-in renderer feature. */
public final class MinecraftOptions {
    private MinecraftOptions() { }

    public static FeatureSettings register(SettingsRegistry registry) {
        var options = allSettings();
        var feature = registry.feature(CausticaConfig.FEATURE).title(DisplayText.literal("Caustica"));
        options.stream().map(Option::group).filter(Objects::nonNull).distinct().forEach(feature::group);
        return feature.options(options).register();
    }

    public static List<Option<?>> allSettings() {
        return Stream.concat(settings().stream(), RendererOptions.settings().stream()).toList();
    }

    public static List<Option<?>> settings() {
        return List.of(
                Rt.ENABLED, Rt.WORKER_THREADS, Rt.Composite.WATER_WAVES,
                Rt.Terrain.ASYNC_DISPATCH_PER_PASS,
                Rt.Terrain.MAX_INFLIGHT_SECTIONS, Rt.Terrain.REBASE_DISTANCE_BLOCKS,
                Rt.Lights.MIN_FILL_RATIO, Rt.Entities.ENABLED, Rt.Entities.PARTICLES_ENABLED,
                Rt.Entities.GLOW_ENABLED, Rt.Entities.NAME_TAGS_ENABLED,
                Rt.Entities.MAX_ORDINARY_ENTITIES, Rt.Entities.MAX_BLOCK_ENTITIES, Rt.Entities.MAX_PARTICLES,
                Rt.Entities.BE_VIEW_CHUNKS, Rt.Entities.BE_BUILDS_PER_FRAME,
                Rt.Overlay.BLOCK_OUTLINE_ENABLED, Rt.Composition.SKY, Ngx.PATH, Slang.PATH);
    }

    public static final class Rt {
        private Rt() { }
        public static final Option<Boolean> ENABLED = bool("caustica.rt", "enabled", true).inGroup("general");
        public static final Option<Integer> WORKER_THREADS = intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);
        public static final class Composite {
            private Composite() { }
            public static final Option<Boolean> WATER_WAVES = bool("caustica.rt.waterWaves", "composite.water-waves", true).inGroup("look");
        }

        public static final class Terrain {
            private Terrain() { }
            public static final Option<Integer> ASYNC_DISPATCH_PER_PASS = intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final Option<Integer> MAX_INFLIGHT_SECTIONS = intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final Option<Integer> REBASE_DISTANCE_BLOCKS = intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);
        }

        public static final class Lights {
            private Lights() { }
            public static final Option<Float> MIN_FILL_RATIO = finiteFloat("caustica.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);
        }

        public static final class Entities {
            private Entities() { }
            public static final Option<Boolean> ENABLED = bool("caustica.rt.entities", "entities.enabled", true).inGroup("entities");
            public static final Option<Boolean> PARTICLES_ENABLED = bool("caustica.rt.particles", "particles.enabled", true).inGroup("entities");
            public static final Option<Boolean> GLOW_ENABLED = bool("caustica.rt.glow", "entities.glow.enabled", true).inGroup("entities");
            public static final Option<Boolean> NAME_TAGS_ENABLED = bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            public static final Option<Integer> MAX_ORDINARY_ENTITIES = intAtLeast("caustica.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final Option<Integer> MAX_BLOCK_ENTITIES = intAtLeast("caustica.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final Option<Integer> MAX_PARTICLES = intAtLeast("caustica.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final Option<Integer> BE_VIEW_CHUNKS = intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final Option<Integer> BE_BUILDS_PER_FRAME = intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 128, 0);
        }

        public static final class Overlay {
            private Overlay() { }
            public static final Option<Boolean> BLOCK_OUTLINE_ENABLED = bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true).inGroup("entities");
        }

        public static final class Composition {
            private Composition() { }
            public static final Option<Optional<String>> SKY = optionalString("caustica.composition.sky", "composition.slots.sky");
        }
    }

    public static final class Ngx {
        private Ngx() { }
        public static final Option<Optional<String>> PATH = optionalString("caustica.ngx.path", "ngx.path");
    }

    public static final class Slang {
        private Slang() { }
        public static final Option<Optional<String>> PATH = optionalString("caustica.slang.path", "slang.path");
    }

    private static Option<Boolean> bool(String key, String path, boolean fallback) {
        return Option.bool(path, fallback).storage(path, key);
    }
    private static Option<Integer> intAtLeast(String key, String path, int fallback, int min) {
        return Option.integer(path, min, Integer.MAX_VALUE, fallback).storage(path, key);
    }
    private static Option<Float> finiteFloat(String key, String path, float fallback) {
        return Option.range(path, -Float.MAX_VALUE, Float.MAX_VALUE, fallback).storage(path, key);
    }
    private static Option<Optional<String>> optionalString(String key, String path) {
        return Option.optionalString(path).storage(path, key);
    }
    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
