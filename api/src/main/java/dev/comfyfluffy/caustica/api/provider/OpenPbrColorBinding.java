package dev.comfyfluffy.caustica.api.provider;

/** Declares how a texture bundle supplies a canonical OpenPBR color parameter. */
public enum OpenPbrColorBinding {
    /** Use the OpenPBR default for this parameter. */
    PARAMETER_DEFAULT,
    /** Use the evaluated {@code base_color} at the same surface point. */
    BASE_COLOR
}
