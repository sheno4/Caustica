/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.internal;

import dev.comfyfluffy.cloudly.api.pass.Pass;
import dev.comfyfluffy.cloudly.api.pass.PostEffectFrame;
import dev.comfyfluffy.cloudly.api.resource.ResourceFactory;
import dev.comfyfluffy.cloudly.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.cloudly.api.vulkan.GpuDevice;
import dev.comfyfluffy.cloudly.sky.MinecraftSkyFrame;
import dev.comfyfluffy.cloudly.settings.OptionValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

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
    private CloudlyOriginalPass active;
    private CloudlyOriginalPass pending;
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
        update(null, false);
    }

    /** Publishes prepared resources only when the host has captured matching lights for this frame. */
    public synchronized void updateForHost(CloudlyOptions.Scene acknowledgedScene) {
        update(acknowledgedScene, true);
    }

    private void update(CloudlyOptions.Scene acknowledgedScene, boolean hostControlled) {
        if (closed) return;
        OptionValues values = options.get();
        boolean selectedEnabled = values.get(CloudlyOptions.ENABLED);
        CloudlyOptions.Scene selected = values.get(CloudlyOptions.SCENE);
        if (!selectedEnabled) {
            if (pending != null) { pending.close(); pending = null; }
            if (active != null) { active.close(); active = null; activeScene = null; }
            if (enabled) LOGGER.info("Original Cloudly sky disabled; native scene resources retired");
            enabled = false;
            requested = null;
            return;
        }
        if (hostControlled && pending != null && pending.resourcesReady() && acknowledgedScene == requested) {
            publishPending();
        }
        if (!enabled || requested != selected) {
            enabled = true;
            requested = selected;
            if (pending != null) { pending.close(); pending = null; }
            if (activeScene != selected) {
                LOGGER.info("Preparing bundled ACE COMBAT sky {}; keeping {} until preparation completes", selected,
                        activeScene == null ? "the current world sky" : activeScene);
                try {
                    pending = new CloudlyOriginalPass(gpu, compute, resources, options, frames, selected);
                } catch (RuntimeException failure) {
                    LOGGER.error("Could not start ACE COMBAT sky {} preparation; keeping {}", selected, activeScene, failure);
                }
            }
        }
        if (pending == null) return;
        var failure = pending.preparationFailure();
        if (failure.isPresent()) {
            pending.close();
            pending = null;
            LOGGER.error("ACE COMBAT sky {} preparation failed; keeping {}", requested,
                    activeScene == null ? "the current world sky" : activeScene, failure.get());
        } else if (pending.resourcesReady() && (!hostControlled || acknowledgedScene == requested)) {
            publishPending();
        }
    }

    private void publishPending() {
        CloudlyOriginalPass previous = active;
        active = pending;
        activeScene = requested;
        pending = null;
        if (previous != null) previous.close();
        LOGGER.info("Activated bundled ACE COMBAT sky {} with fresh cloud history", activeScene);
    }

    public synchronized boolean resourcesReady() { return !closed && active != null; }

    public synchronized CloudlySkyPreset skyPreset() { return active == null ? null : active.skyPreset(); }
    public record ReadyScene(CloudlyOptions.Scene scene, CloudlySkyPreset sky) { }
    public synchronized ReadyScene readyScene() {
        if (pending != null && pending.resourcesReady()) return new ReadyScene(requested, pending.skyPreset());
        return active == null ? null : new ReadyScene(activeScene, active.skyPreset());
    }
    public synchronized CloudlySurfaceShadow.Snapshot surfaceShadow() { return active == null ? null : active.surfaceShadow(); }
    public synchronized CloudlySurfaceShadow.Snapshot preparedSurfaceShadow() {
        return active == null ? null : active.preparedSurfaceShadow();
    }
    public synchronized CloudlyOriginalPass.RayResources preparedRayResources() {
        return active == null ? null : active.preparedRayResources();
    }

    public synchronized void prepareWorld(PostEffectFrame frame) {
        if (!closed && active != null) active.prepareWorld(frame);
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
        if (pending != null) { pending.close(); pending = null; }
        if (active != null) { active.close(); active = null; }
    }
}
