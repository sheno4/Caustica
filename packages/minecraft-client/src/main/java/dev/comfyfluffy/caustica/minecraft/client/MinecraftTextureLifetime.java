package dev.comfyfluffy.caustica.minecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Minecraft texture view counts and its destruction queue belong to the render thread. */
public final class MinecraftTextureLifetime {
    private static final Releases RELEASES = new Releases();

    private MinecraftTextureLifetime() { }

    public static void retain(VulkanGpuTexture texture) {
        RenderSystem.assertOnRenderThread();
        texture.addViews();
    }

    /** The host lease remains counted until the render thread consumes its final release. */
    public static void release(VulkanGpuTexture texture) {
        if (RenderSystem.isOnRenderThread()) texture.removeViews();
        else RELEASES.add(texture::removeViews);
    }

    public static void drain() {
        RenderSystem.assertOnRenderThread();
        RELEASES.drain();
    }

    static final class Releases {
        private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

        void add(Runnable release) { pending.add(release); }

        void drain() {
            Runnable release;
            while ((release = pending.poll()) != null) release.run();
        }
    }
}
