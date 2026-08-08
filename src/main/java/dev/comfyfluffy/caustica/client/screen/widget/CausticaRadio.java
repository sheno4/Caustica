package dev.comfyfluffy.caustica.client.screen.widget;

import dev.comfyfluffy.caustica.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.client.settings.SettingControl;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * One candidate of a choice. Several share a {@link SettingControl.ChoiceControl}, and selection is simply
 * whether the control's value equals this row's — no group object owns mutual exclusion, because the store
 * already holds exactly one answer.
 *
 * <p>Used for the slot bindings, where two candidates are a choice to offer rather than a conflict to
 * resolve, and the default is always the built-in so installing an extension changes nothing until asked.
 */
public final class CausticaRadio<T> extends AbstractButton {
    private final SettingControl.ChoiceControl<T> control;
    private final T value;
    private final Font font;
    private final int accent;
    private final Runnable onChanged;
    private final boolean isDefault;

    public CausticaRadio(SettingControl.ChoiceControl<T> control, T value, Font font, int accent,
                         Runnable onChanged) {
        super(0, 0, 0, CausticaTheme.ROW_HEIGHT, Component.empty());
        this.control = control;
        this.value = value;
        this.font = font;
        this.accent = accent;
        this.onChanged = onChanged;
        this.isDefault = value.equals(control.defaultValue());
    }

    public boolean selected() {
        return control.get().equals(value);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (!selected()) {
            control.set(value);
            onChanged.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int right = getX() + getWidth();
        int textY = CausticaPaint.textBaseline(font, getY(), getHeight());
        boolean selected = selected();

        if (isHovered) {
            graphics.fill(getX(), getY(), right, getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }

        // A 7x7 square with a filled 3x3 core when chosen; a circle is not worth the fills at this size.
        int markX = getX() + CausticaTheme.CONTENT_PAD;
        int markY = getY() + (getHeight() - 7) / 2;
        graphics.outline(markX, markY, 7, 7, selected ? accent : CausticaTheme.TOGGLE_OFF);
        if (selected) {
            graphics.fill(markX + 2, markY + 2, markX + 5, markY + 5, accent);
        }

        int labelX = markX + 12;
        CausticaPaint.textClipped(graphics, font, control.labelOf(value), labelX, textY,
                right - labelX - CausticaTheme.CONTENT_PAD - (isDefault ? 48 : 0),
                CausticaTheme.textColour(true, selected || isHovered));

        if (isDefault) {
            CausticaPaint.textRight(graphics, font,
                    Component.translatable("caustica.slot.default_suffix"),
                    right - CausticaTheme.CONTENT_PAD, textY, CausticaTheme.TEXT_DISABLED);
        }

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, markX, markY, 7, 7, accent);
        }
        handleCursor(graphics);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, control.labelOf(value));
        output.add(NarratedElementType.USAGE, Component.translatable(
                selected() ? "options.on" : "options.off"));
    }
}
