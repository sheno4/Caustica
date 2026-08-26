package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.option.OptionLookup;
import dev.comfyfluffy.caustica.api.option.OptionValues;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;
import java.util.Objects;

/** Process-wide access to the registry and option store installed by the current host adapter. */
public final class CausticaApi {
    public static final String VERSION = "0.3.0";
    public static final String ENTRYPOINT = "caustica";
    private static CausticaApi instance;

    private final CausticaRegistry registry;
    private final OptionLookup options;
    private final RendererChannels channels;

    private CausticaApi(CausticaRegistry registry, OptionLookup options, RendererChannels channels) {
        this.registry = registry;
        this.options = options;
        this.channels = channels;
    }

    public static synchronized void initialize(CausticaRegistry installedRegistry,
                                               OptionLookup installedOptions,
                                               RendererChannels installedChannels) {
        Objects.requireNonNull(installedRegistry, "installedRegistry");
        Objects.requireNonNull(installedOptions, "installedOptions");
        Objects.requireNonNull(installedChannels, "installedChannels");
        if (instance != null) {
            throw new IllegalStateException("Caustica API is already initialized");
        }
        installedRegistry.selection();
        instance = new CausticaApi(installedRegistry, installedOptions, installedChannels);
    }

    /** Returns the API installed by the host adapter. */
    public static synchronized CausticaApi getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return instance;
    }

    public CausticaRegistry registry() {
        return registry;
    }

    /** The current values for one registered feature's declared options. */
    public OptionValues options(ResourceId featureId) {
        return options.options(Objects.requireNonNull(featureId, "featureId"));
    }

    /**
     * Vulkan device services: the renderer's device, its allocator, and
     * {@link GpuDevice#retireAfterUse}, which is how anything an extension allocated is released.
     *
     * <p>Here rather than on a channel because a source of retained data owns GPU buffers whether or not
     * it also owns a pass, and the lifetime primitive has to be reachable from wherever it frees them.
     *
     * @throws IllegalStateException before a render session exists
     */
    public GpuDevice gpu() {
        return channels.gpu();
    }

    /**
     * Scene identity and lifetime. A scene is a coordinate system, an acceleration structure, and a set of
     * lights; placements and lights are made into one, meshes and materials are not.
     */
    public SceneChannel scenes() {
        return channels.scenes();
    }

    /**
     * Retained meshes and their placements. One collection, renderer-owned; ids are issued so nothing needs
     * a per-source namespace. Long-lived and thread-safe — resolve it once and keep it, or call this each
     * time.
     */
    public GeometryChannel geometry() {
        return channels.geometry();
    }

    /** Registered materials. Registration is synchronous and the id it returns is usable on return. */
    public MaterialChannel materials() {
        return channels.materials();
    }

    /** Retained lights, in the same shape as retained geometry and scoped to a scene the same way. */
    public LightChannel lights() {
        return channels.lights();
    }

    /** Host-neutral option lookup used by render-session infrastructure. */
    public OptionLookup optionLookup() {
        return options;
    }
}
