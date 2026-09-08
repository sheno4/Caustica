package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingGroup;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * A group's title row, and the thing that collapses it.
 *
 * <p>Two shapes, decided by whether the group declared a header bool. With one, this row <em>is</em> that
 * switch: turning bloom off hides its parameters because they no longer mean anything. Without one, the row
 * carries only a caret and remembers its state for the session — the right shape wherever there is no state
 * in which the rows fail to apply, as for a sky that every escaped ray still has to hit.
 */
public final class CausticaGroupHeader extends AbstractButton {
    private final SettingGroup group;
    private final Font font;
    private final int accent;
    private final Runnable onToggled;
    private boolean expandedByCaret = true;

    public CausticaGroupHeader(SettingGroup group, Font font, int accent, Runnable onToggled) {
        super(0, 0, 0, CausticaTheme.GROUP_HEADER_HEIGHT, Component.empty());
        this.group = group;
        this.font = font;
        this.accent = accent;
        this.onToggled = onToggled;
    }

    public SettingGroup group() {
        return group;
    }

    public boolean rowsVisible() {
        return group.rowsVisible(expandedByCaret);
    }

    @Override
    public boolean isActive() {
        return super.isActive() && (group.header() == null || group.header().enabled());
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (!isActive()) return;
        if (group.header() != null) {
            group.header().set(!group.header().get());
        } else {
            expandedByCaret = !expandedByCaret;
        }
        onToggled.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int right = getX() + getWidth();
        int textY = CausticaPaint.textBaseline(font, getY(), getHeight());
        boolean open = rowsVisible();
        boolean enabled = isActive();

        if (isHovered && enabled) {
            graphics.fill(getX(), getY(), right, getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }

        int caretX = getX() + CausticaTheme.CONTENT_PAD;
        caret(graphics, caretX, textY, open, enabled ? accent : CausticaTheme.TEXT_DISABLED);

        int titleX = caretX + 10;
        graphics.text(font, group.title(), titleX, textY,
                enabled ? CausticaTheme.TEXT_PRIMARY : CausticaTheme.TEXT_DISABLED, false);

        // The header bool doubles as this row's switch, drawn where a row's control column sits.
        if (group.header() != null) {
            int switchX = right - CausticaTheme.CONTENT_PAD - CausticaTheme.TOGGLE_WIDTH;
            int switchY = getY() + (getHeight() - CausticaTheme.TOGGLE_HEIGHT) / 2;
            boolean on = group.header().get();
            int trackColour = on ? accent : CausticaTheme.TOGGLE_OFF;
            graphics.fill(switchX, switchY, switchX + CausticaTheme.TOGGLE_WIDTH,
                    switchY + CausticaTheme.TOGGLE_HEIGHT,
                    enabled ? trackColour : CausticaTheme.dimmed(trackColour));
            int knobX = on ? switchX + CausticaTheme.TOGGLE_WIDTH - CausticaTheme.TOGGLE_HEIGHT : switchX;
            int knobColour = isHovered || isFocused() ? CausticaTheme.KNOB_HOVER : CausticaTheme.KNOB;
            graphics.fill(knobX, switchY, knobX + CausticaTheme.TOGGLE_HEIGHT,
                    switchY + CausticaTheme.TOGGLE_HEIGHT,
                    enabled ? knobColour : CausticaTheme.dimmed(knobColour));
        }

        graphics.fill(getX() + CausticaTheme.CONTENT_PAD, getY() + getHeight() - 1,
                right - CausticaTheme.CONTENT_PAD, getY() + getHeight(), CausticaTheme.DIVIDER);

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, accent);
        }
        handleCursor(graphics);
    }

    /** A solid triangle from stacked fills — down when open, right when closed. */
    private static void caret(GuiGraphicsExtractor graphics, int x, int y, boolean open, int colour) {
        if (open) {
            for (int row = 0; row < 3; row++) {
                graphics.fill(x + row, y + 2 + row, x + 7 - row, y + 3 + row, colour);
            }
        } else {
            for (int column = 0; column < 3; column++) {
                graphics.fill(x + 2 + column, y + 1 + column, x + 3 + column, y + 8 - column, colour);
            }
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, group.title());
        output.add(NarratedElementType.USAGE, Component.translatable(
                rowsVisible() ? "caustica.screen.group.expanded" : "caustica.screen.group.collapsed"));
    }
}
