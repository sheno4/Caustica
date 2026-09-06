package dev.comfyfluffy.caustica.settings;

/**
 * Optional process-lifetime settings contribution implemented alongside a Caustica extension.
 *
 * <p>The host collects declarations before loading persisted values, then supplies settings access
 * before extension registration. Both callbacks are independent of Vulkan device and render-session lifetime.
 */
@FunctionalInterface
public interface CausticaSettingsExtension {
    /** Declare this extension's settings in the host registry. */
    void registerSettings(SettingsRegistry registry);

    /**
     * Supplies shared settings access after all declarations and persisted values have been loaded.
     * The host calls this once per extension instance, before rendering or Minecraft registration.
     * Capture this access for later snapshots; it is independent of render-session lifetime.
     */
    default void settingsReady(SettingsAccess settings) { }
}
