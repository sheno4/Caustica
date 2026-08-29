package dev.comfyfluffy.caustica.spi.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Optional host debug-utils integration for renderer-owned Vulkan objects and command scopes. */
public interface DebugMarkers {
    DebugMarkers NONE = new DebugMarkers() {
        @Override
        public void nameObject(int objectType, long handle, String label) {
        }

        @Override
        public Scope begin(VkCommandBuffer commandBuffer, String label) {
            return Scope.NOOP;
        }
    };

    void nameObject(int objectType, long handle, String label);

    Scope begin(VkCommandBuffer commandBuffer, String label);

    interface Scope extends AutoCloseable {
        Scope NOOP = () -> { };

        @Override
        void close();
    }
}
