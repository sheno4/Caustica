package dev.comfyfluffy.caustica.engine.frame;

/** One host-authored damage overlay in absolute scene coordinates. */
public record DamageOverlay(int worldX, int worldY, int worldZ, int textureSlot) {
}
