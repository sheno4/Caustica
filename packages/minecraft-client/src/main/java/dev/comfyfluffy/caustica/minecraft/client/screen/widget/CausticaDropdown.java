package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * A closed set of values as one row plus a list that opens on click, rather than one row per value. A
 * ten-entry debug view or a five-step quality mode is a list to pick from, not a column of radio buttons to
 * scroll past.
 *
 * <p>The open list would be clipped by the scroll pane's scissor, so the pane does not draw it: the screen
 * holds whichever dropdown is open and draws its list after everything else, and offers it pointer events
 * first. This widget therefore exposes {@link #extractPopup} and {@link #clickPopup} instead of handling
 * either itself.
 */
public final class CausticaDropdown<T> extends AbstractButton {
    private static final int POPUP_ROW_HEIGHT = 12;
    private static final int POPUP_WIDTH = 132;

    private final SettingControl.ChoiceControl<T> control;
    private final Font font;
    private final int accent;
    private final Consumer<CausticaDropdown<?>> onToggled;
    private boolean expanded;

    public CausticaDropdown(SettingControl.ChoiceControl<T> control, Font font, int accent,
                            Consumer<CausticaDropdown<?>> onToggled) {
        super(0, 0, 0, CausticaTheme.ROW_HEIGHT, Component.empty());
        this.control = control;
        this.font = font;
        this.accent = accent;
        this.onToggled = onToggled;
        if (control.tooltip() != null) {
            setTooltip(Tooltip.create(control.tooltip()));
        }
    }

    public boolean expanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    @Override
    public boolean isActive() {
        return super.isActive() && control.enabled();
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (isActive()) {
            onToggled.accept(this);
        }
    }

    private int popupLeft() {
        return getX() + getWidth() - CausticaTheme.CONTENT_PAD - POPUP_WIDTH;
    }

    private int popupTop() {
        return getY() + getHeight();
    }

    private int popupHeight() {
        return control.choices().size() * POPUP_ROW_HEIGHT + 2;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean enabled = control.enabled();
        int right = getX() + getWidth();
        int textY = CausticaPaint.textBaseline(font, getY(), getHeight());

        if (isHovered && enabled) {
            graphics.fill(getX(), getY(), right, getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }

        int valueRight = right - CausticaTheme.CONTENT_PAD - 8;
        int valueLeft = valueRight - CausticaTheme.VALUE_WIDTH - 40;
        CausticaPaint.textClipped(graphics, font, control.label(), getX() + CausticaTheme.CONTENT_PAD, textY,
                valueLeft - getX() - CausticaTheme.CONTENT_PAD * 2,
                CausticaTheme.textColour(enabled, isHovered || isFocused()));

        CausticaPaint.textRight(graphics, font, control.labelOf(control.get()), valueRight, textY,
                enabled ? CausticaTheme.TEXT_VALUE : CausticaTheme.TEXT_DISABLED);
        caret(graphics, valueRight + 3, textY + 2, expanded, enabled ? accent : CausticaTheme.TEXT_DISABLED);

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, valueLeft, getY() + 2, right - valueLeft - 4,
                    getHeight() - 4, accent);
        }
        handleCursor(graphics);
    }

    /** Drawn by the screen after the content pane, so the pane's scissor does not cut it off. */
    public void extractPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = popupLeft();
        int top = popupTop();
        int height = popupHeight();
        graphics.fill(left, top, left + POPUP_WIDTH, top + height, CausticaTheme.POPUP_BODY);
        graphics.outline(left, top, POPUP_WIDTH, height, accent);

        int y = top + 1;
        for (T choice : control.choices()) {
            boolean selected = choice.equals(control.get());
            boolean hovered = mouseX >= left && mouseX < left + POPUP_WIDTH
                    && mouseY >= y && mouseY < y + POPUP_ROW_HEIGHT;
            if (hovered) {
                graphics.fill(left + 1, y, left + POPUP_WIDTH - 1, y + POPUP_ROW_HEIGHT, CausticaTheme.ROW_HOVER);
            }
            if (selected) {
                graphics.fill(left + 1, y, left + 3, y + POPUP_ROW_HEIGHT, accent);
            }
            graphics.text(font, control.labelOf(choice), left + 7,
                    CausticaPaint.textBaseline(font, y, POPUP_ROW_HEIGHT),
                    selected || hovered ? CausticaTheme.TEXT_PRIMARY : CausticaTheme.TEXT_SECONDARY, false);
            y += POPUP_ROW_HEIGHT;
        }
    }

    /**
     * Offered the click before anything else while open. Returns whether the click landed in the list; a
     * click elsewhere closes it without selecting, which is what the screen uses the false for.
     */
    public boolean clickPopup(double mouseX, double mouseY) {
        if (!isActive()) return false;
        int left = popupLeft();
        int top = popupTop();
        if (mouseX < left || mouseX >= left + POPUP_WIDTH || mouseY < top || mouseY >= top + popupHeight()) {
            return false;
        }
        int index = (int) ((mouseY - top - 1) / POPUP_ROW_HEIGHT);
        List<T> choices = control.choices();
        if (index >= 0 && index < choices.size()) {
            control.set(choices.get(index));
        }
        return true;
    }

    private static void caret(GuiGraphicsExtractor graphics, int x, int y, boolean open, int colour) {
        for (int row = 0; row < 3; row++) {
            int inset = open ? row : 2 - row;
            graphics.fill(x + inset, y + row, x + 5 - inset, y + row + 1, colour);
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, control.label());
        output.add(NarratedElementType.USAGE, control.labelOf(control.get()));
    }
}
