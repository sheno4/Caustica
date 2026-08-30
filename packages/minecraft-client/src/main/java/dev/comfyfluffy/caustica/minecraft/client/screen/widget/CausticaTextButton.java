package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * The footer's actions and the header's close mark. Flat text with a hover wash rather than a bordered
 * button — the screen's own panels already establish where things sit, so a box around a word adds only
 * weight.
 */
public final class CausticaTextButton extends AbstractButton {
    private final Font font;
    private final int accent;
    private final Runnable action;
    private final boolean glyphOnly;

    public static CausticaTextButton text(Component label, Font font, int accent, Runnable action) {
        return new CausticaTextButton(label, font, accent, action, false);
    }

    /** The header's close affordance: an X drawn from two diagonals rather than a font glyph. */
    public static CausticaTextButton close(Font font, int accent, Runnable action) {
        return new CausticaTextButton(Component.translatable("caustica.screen.close"), font, accent, action, true);
    }

    private CausticaTextButton(Component label, Font font, int accent, Runnable action, boolean glyphOnly) {
        super(0, 0, glyphOnly ? 16 : font.width(label) + 16, glyphOnly ? 16 : 16, label);
        this.font = font;
        this.accent = accent;
        this.action = action;
        this.glyphOnly = glyphOnly;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (isHovered && active) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }
        int colour = active
                ? (isHovered || isFocused() ? accent : CausticaTheme.TEXT_SECONDARY)
                : CausticaTheme.TEXT_DISABLED;

        if (glyphOnly) {
            cross(graphics, getX() + 5, getY() + 5, colour);
        } else {
            graphics.text(font, getMessage(), getX() + 8,
                    CausticaPaint.textBaseline(font, getY(), getHeight()), colour, false);
        }

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, getX(), getY(), getWidth(), getHeight(), accent);
        }
        handleCursor(graphics);
    }

    private static void cross(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        for (int step = 0; step < 6; step++) {
            graphics.fill(x + step, y + step, x + step + 1, y + step + 1, colour);
            graphics.fill(x + 5 - step, y + step, x + 6 - step, y + step + 1, colour);
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
