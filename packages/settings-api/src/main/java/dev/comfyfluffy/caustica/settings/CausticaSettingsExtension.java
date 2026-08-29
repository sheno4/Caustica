package dev.comfyfluffy.caustica.settings;

/**
 * Optional process-lifetime settings contribution implemented alongside a Caustica extension.
 *
 * <p>The host invokes this before loading persisted values and before any render session opens. Settings
 * declarations are independent of Vulkan device and render-session lifetime.
 */
@FunctionalInterface
public interface CausticaSettingsExtension {
    /** Declare this extension's settings in the host registry. */
    void registerSettings(SettingsRegistry registry);
}
