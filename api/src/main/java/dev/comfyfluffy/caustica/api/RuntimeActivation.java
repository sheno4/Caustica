package dev.comfyfluffy.caustica.api;

/** Controls when a feature's runtime contributions are instantiated for an RT session. */
public enum RuntimeActivation {
    /** Instantiate whenever an RT session exists, independent of the selected composition. */
    ALWAYS,
    /**
     * Instantiate only when this feature owns a currently selected engine slot. A feature that declares
     * runtime contributions must bind at least one slot to use this mode.
     */
    SELECTED_SLOT
}
