package dev.comfyfluffy.caustica.client.screen.widget;

import dev.comfyfluffy.caustica.client.screen.CausticaTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Drawing shared by the widgets, so one visual idea has one implementation. */
public final class CausticaPaint {
    private CausticaPaint() {
    }

    /**
     * A filled rect with a one-pixel lighter top and left edge. Minecraft's own surfaces read as raised
     * because of exactly this, and it costs three fills rather than a nine-slice texture.
     */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int body) {
        graphics.fill(x, y, x + width, y + height, body);
        graphics.fill(x, y, x + width, y + 1, CausticaTheme.EDGE_HIGHLIGHT);
        graphics.fill(x, y, x + 1, y + height, CausticaTheme.EDGE_HIGHLIGHT);
    }

    /** Keyboard focus ring. Drawn outside the control's own bounds so it never covers what it marks. */
    public static void focusRing(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int accent) {
        graphics.outline(x - 1, y - 1, width + 2, height + 2, accent);
    }

    /**
     * Dots joining a label to its control. Skipped when the gap is too small to read as a leader rather
     * than as debris.
     */
    public static void dotLeader(GuiGraphicsExtractor graphics, int fromX, int toX, int y) {
        if (toX - fromX < CausticaTheme.LEADER_MINIMUM_GAP) {
            return;
        }
        for (int x = fromX + CausticaTheme.LEADER_PITCH; x < toX; x += CausticaTheme.LEADER_PITCH) {
            graphics.fill(x, y, x + 1, y + 1, CausticaTheme.LEADER_DOT);
        }
    }

    /** Right-aligned text, for a value column that must not shift as digits come and go. */
    public static void textRight(GuiGraphicsExtractor graphics, Font font, Component text, int rightX, int y,
                                 int colour) {
        graphics.text(font, text, rightX - font.width(text), y, colour, false);
    }

    /** Truncates to fit, so a long label degrades instead of overrunning its neighbour. */
    public static void textClipped(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y,
                                   int maxWidth, int colour) {
        if (font.width(text) <= maxWidth) {
            graphics.text(font, text, x, y, colour, false);
            return;
        }
        String plain = font.plainSubstrByWidth(text.getString(), maxWidth - font.width("..."));
        graphics.text(font, plain + "...", x, y, colour, false);
    }

    /** Vertically centres one line of text in a row of the given height. */
    public static int textBaseline(Font font, int y, int height) {
        return y + (height - font.lineHeight) / 2;
    }
}
