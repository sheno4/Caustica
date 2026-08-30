package dev.comfyfluffy.caustica.renderer.denoising;

/** Encoding shared by the diffuse and specular radiance-hit-distance signals. */
public enum DenoiserSignalEncoding {
    /** RGB stores linear radiance and alpha stores an absolute world-space hit distance. */
    LINEAR_RGB_ABSOLUTE_HIT_DISTANCE,

    /** RGB stores YCoCg radiance and alpha stores the backend's normalized hit distance. */
    YCOCG_NORMALIZED_HIT_DISTANCE
}
