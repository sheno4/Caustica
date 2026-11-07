package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.settings.OptionValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;
import java.util.function.Consumer;

/** Publishes a prepared native scene and its lighting together at the start of a world frame. */
public final class CloudlyScenePass implements Pass<PostEffectFrame> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudlyScenePass.class);
    private final GpuDevice gpu;
    private final GpuComputeQueue compute;
    private final ResourceFactory resources;
    private final Supplier<OptionValues> options;
    private final Supplier<MinecraftSkyFrame> frames;
    private CloudlyOptions.Scene requested;
    private CloudlyOptions.Scene activeScene;
    private CloudlyOptions.Scene pendingScene;
    private CloudlyOriginalPass active;
    private CloudlyOriginalPass pending;
    private long retryAtNanos;
    private boolean enabled;
    private boolean closed;

    public CloudlyScenePass(GpuDevice gpu, GpuComputeQueue compute, ResourceFactory resources,
                            Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames) {
        this.gpu = gpu;
        this.compute = compute;
        this.resources = resources;
        this.options = options;
        this.frames = frames;
    }

    /** Called before light and atmosphere recording, so both consume the same published scene. */
    public synchronized void update() {
        if (closed) return;
        OptionValues values = options.get();
        boolean selectedEnabled = values.get(CloudlyOptions.ENABLED);
        CloudlyOptions.Scene selected = values.get(CloudlyOptions.SCENE);
        if (!selectedEnabled) {
            if (pending != null) { pending.close(); pending = null; pendingScene = null; }
            if (active != null) { active.close(); active = null; activeScene = null; }
            if (enabled) LOGGER.info("Original Cloudly sky disabled; native scene resources retired");
            enabled = false;
            requested = null;
            retryAtNanos = 0;
            return;
        }
        if (!enabled || requested != selected) {
            enabled = true;
            requested = selected;
            retryAtNanos = 0;
        }
        // Let preparation finish before publishing or discarding it, so menu changes cannot interrupt
        // archive reads or invalidate another scene's pending uploads.
        if (pending != null) {
            var failure = pending.preparationFailure();
            if (failure.isPresent()) {
                CloudlyOptions.Scene failed = pendingScene;
                pending.close();
                pending = null;
                pendingScene = null;
                retryAtNanos = System.nanoTime() + 1_000_000_000L;
                LOGGER.error("ACE COMBAT sky {} preparation failed; keeping {}", failed,
                        activeScene == null ? "the current world sky" : activeScene, failure.get());
                return;
            }
            if (!pending.resourcesReady()) return;
            if (pendingScene != requested) {
                pending.close();
                pending = null;
                pendingScene = null;
                return;
            }
            CloudlyOriginalPass previous = active;
            active = pending;
            activeScene = pendingScene;
            pending = null;
            pendingScene = null;
            if (previous != null) previous.close();
            LOGGER.info("Activated bundled ACE COMBAT sky {} with fresh cloud history", activeScene);
            return;
        }
        if (activeScene == selected) return;
        long now = System.nanoTime();
        if (now < retryAtNanos) return;
        LOGGER.info("Preparing bundled ACE COMBAT sky {}; keeping {} until preparation completes", selected,
                activeScene == null ? "the current world sky" : activeScene);
        try {
            pending = new CloudlyOriginalPass(gpu, compute, resources, options, frames, selected);
            pendingScene = selected;
        } catch (RuntimeException failure) {
            retryAtNanos = now + 1_000_000_000L;
            LOGGER.error("Could not start ACE COMBAT sky {} preparation; keeping {}", selected, activeScene, failure);
        }
    }

    public synchronized CloudlySkyPreset skyPreset() { return active == null ? null : active.skyPreset(); }
    public synchronized CloudlySurfaceShadow.Snapshot surfaceShadow() { return active == null ? null : active.surfaceShadow(); }
    public synchronized Object environmentIdentity() { return active; }
    public synchronized long environmentRevision() { return active == null ? 0 : active.environmentRevision(); }

    /** Call after this scene's main recording; callbacks consume unexposed native linear BT.709 radiance. */
    public synchronized boolean recordEnvironmentFace(PostEffectFrame frame, MinecraftSkyFrame captured,
                                                       OptionValues values,
                                                       Consumer<GpuDescriptorIndex.Resource> noDiscs,
                                                       Consumer<GpuDescriptorIndex.Resource> withDiscs) {
        return !closed && active != null
                && active.recordEnvironmentFace(frame, captured, values, noDiscs, withDiscs);
    }

    /** A different world may reuse frame numbers and coordinates, but cannot reuse its predecessor's history. */
    public synchronized void invalidateHistory() {
        if (active != null) active.invalidateHistory();
    }

    @Override public synchronized void record(PostEffectFrame frame) {
        if (!closed && active != null) active.record(frame);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pending != null) { pending.close(); pending = null; pendingScene = null; }
        if (active != null) { active.close(); active = null; }
        retryAtNanos = 0;
    }
}
