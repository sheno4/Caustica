/**
 * Frame-coherent publication of retained scene changes.
 *
 * <p>Only changes that must join the frame currently being collected use this package. Independently
 * timed retained work uses the geometry and light channels directly.
 */
package dev.comfyfluffy.caustica.api.frame;
