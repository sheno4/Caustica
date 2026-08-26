package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.material.MaterialChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.shader.ShaderCompiler;
import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;

import java.util.Objects;

/**
 * Process-wide access to the renderer's channels, installed by the current host adapter.
 *
 * <p>Everything an extension contributes goes through one of these, and every one of them takes additions
 * and removals at any time. There is no declaration phase and no composition closure to be inside of: an
 * extension that is switched off drops what it added, and one switched back on adds it again.
 */
public final class CausticaApi {
    public static final String VERSION = "0.3.0";
    private static CausticaApi instance;

    private final RendererChannels channels;

    private CausticaApi(RendererChannels channels) {
        this.channels = channels;
    }

    static synchronized void install(RendererChannels installedChannels) {
        Objects.requireNonNull(installedChannels, "installedChannels");
        if (instance != null) {
            throw new IllegalStateException("Caustica API is already initialized");
        }
        instance = new CausticaApi(installedChannels);
    }

    /** Returns the API installed by the host adapter. */
    public static synchronized CausticaApi getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Caustica API has not been initialized by a host adapter");
        }
        return instance;
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
     * The host's Slang toolchain, with the renderer's own module search paths already on it, for compiling
     * an extension's own pass shaders. Reachable whenever, rather than posted to a pass at a boundary,
     * because a pass builds its pipelines when it decides to and nothing else needs to know.
     */
    public ShaderCompiler shaderCompiler() {
        return channels.shaderCompiler();
    }

    /**
     * What is compiled into the world program — surfaces, environments, emission profiles, surface
     * modifiers. Added and dropped like any other retained object; the renderer coalesces recompiles.
     */
    public ProgramChannel program() {
        return channels.program();
    }

    /** The passes currently recording into a frame. An extension hands over its own instances. */
    public PassChannel passes() {
        return channels.passes();
    }

    /** Sources that want lifecycle notifications or frame-coherent scene submission. */
    public ProviderChannel providers() {
        return channels.providers();
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
}
