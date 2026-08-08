package dev.comfyfluffy.caustica.client.screen;

import net.minecraft.util.ARGB;

/**
 * Palette and metrics for the Caustica settings screen. Every pixel is drawn from these constants with
 * {@code fill}/{@code text} primitives — no vanilla sprite, no nine-slice, no widget texture.
 *
 * <p><b>Two constraints shape the palette, both from how this screen reaches the display.</b> Everything the
 * GUI draws is redirected into {@code RtUiOverlay}'s transparent {@code RGBA8_UNORM} target and composited
 * back over the scene, so alpha is real: any surface under text is near-opaque here, and only the backdrop
 * and the hover wash are meaningfully translucent. And because that overlay is SDR-authored and, in HDR, is
 * composited at a fixed nit level beside a scene that may peak far above it, <b>nothing uses brightness as a
 * cue</b> — accents differentiate by hue and saturation alone. No glows, no "brighter means selected".
 */
public final class CausticaTheme {
    private CausticaTheme() {
    }

    // Layout, on an 8px grid.
    public static final int HEADER_HEIGHT = 32;
    public static final int FOOTER_HEIGHT = 32;
    public static final int SIDEBAR_WIDTH = 120;
    /** Sidebar width below {@link #NARROW_GUI_WIDTH}, where the full width would eat a third of the screen. */
    public static final int SIDEBAR_WIDTH_NARROW = 88;
    public static final int NARROW_GUI_WIDTH = 360;
    public static final int CONTENT_PAD = 10;
    public static final int ROW_HEIGHT = 20;
    public static final int GROUP_HEADER_HEIGHT = 22;
    public static final int GROUP_GAP = 6;
    public static final int NAV_ITEM_HEIGHT = 18;
    public static final int NAV_HEADING_HEIGHT = 16;
    public static final int ACCENT_BAR_WIDTH = 3;
    public static final int SLIDER_WIDTH = 96;
    public static final int VALUE_WIDTH = 56;
    public static final int SCROLLBAR_WIDTH = 4;
    public static final int TRACK_HEIGHT = 3;
    public static final int KNOB_WIDTH = 5;
    public static final int KNOB_HEIGHT = 11;
    public static final int TOGGLE_WIDTH = 22;
    public static final int TOGGLE_HEIGHT = 11;
    public static final int LEADER_PITCH = 3;
    /** Below this the dot leader is noise rather than a guide. */
    public static final int LEADER_MINIMUM_GAP = 16;

    // Backdrop.
    public static final int BACKDROP_TOP = 0xD8080B12;
    public static final int BACKDROP_BOTTOM = 0xE605070C;

    // Chrome. 0xF0 rather than vanilla's 0xC0 because UI composited at 200 nits beside a 1000-nit scene
    // reads dim, and this must stay legible at the 80-nit floor.
    public static final int HEADER_BODY = 0xF0080B11;
    public static final int SIDEBAR_BODY = 0xF00A0D14;
    public static final int CONTENT_BODY = 0xF00E1119;
    public static final int ROW_ALT = 0xF0121722;
    public static final int FOOTER_BODY = 0xF0080B11;
    public static final int DIVIDER = 0xFF1B2231;
    /** One-pixel top/left inner edge — the bevel cue that keeps this reading as Minecraft. */
    public static final int EDGE_HIGHLIGHT = 0xFF2A3446;

    // Text.
    public static final int TEXT_PRIMARY = 0xFFE8EDF5;
    public static final int TEXT_SECONDARY = 0xFF9AA6BA;
    public static final int TEXT_DISABLED = 0xFF5A6478;
    public static final int TEXT_VALUE = 0xFFFFFFFF;
    public static final int LEADER_DOT = 0xFF2A3446;

    // Controls.
    public static final int TRACK = 0xFF1B2231;
    public static final int KNOB = 0xFFE8EDF5;
    public static final int KNOB_HOVER = 0xFFFFFFFF;
    public static final int TOGGLE_OFF = 0xFF2A3446;
    public static final int ROW_HOVER = 0x14FFFFFF;
    public static final int SCROLLBAR_TRACK = 0xFF141A26;

    /** Section accents. Hues from Minecraft's own materials rather than arbitrary saturated colour. */
    public static final int ACCENT_ENGINE = 0xFF4FC3F7;
    public static final int ACCENT_COMPOSITION = 0xFFB388FF;
    public static final int ACCENT_BUILTIN = 0xFFFFB74D;

    public static int sidebarWidth(int guiWidth) {
        return guiWidth < NARROW_GUI_WIDTH ? SIDEBAR_WIDTH_NARROW : SIDEBAR_WIDTH;
    }

    /** The lighter fill a control takes while it is being dragged. */
    public static int active(int accent) {
        return ARGB.srgbLerp(0.25f, accent, 0xFFFFFFFF);
    }

    /** Greys a control's colour without changing its hue, so a disabled row still reads as itself. */
    public static int dimmed(int argb) {
        return ARGB.multiplyAlpha(argb, 0.4f);
    }

    public static int textColour(boolean enabled, boolean emphasised) {
        if (!enabled) {
            return TEXT_DISABLED;
        }
        return emphasised ? TEXT_PRIMARY : TEXT_SECONDARY;
    }
}
