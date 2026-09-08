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

/**
 * A boolean row drawn as a sliding switch.
 *
 * <p>Extends {@link AbstractButton} for click, Enter/Space, click sound and narration. That class paints
 * nothing on its own — its vanilla skin lives in a separate {@code extractDefaultSprite} that subclasses opt
 * into — so simply not calling it leaves the appearance entirely to {@link #extractContents}.
 */
public final class CausticaToggle extends AbstractButton {
    private final SettingControl.BoolControl control;
    private final Font font;
    private final int accent;
    private final Runnable onChanged;

    public CausticaToggle(SettingControl.BoolControl control, Font font, int accent, Runnable onChanged) {
        super(0, 0, 0, CausticaTheme.ROW_HEIGHT, Component.empty());
        this.control = control;
        this.font = font;
        this.accent = accent;
        this.onChanged = onChanged;
        if (control.tooltip() != null) {
            setTooltip(Tooltip.create(control.tooltip()));
        }
    }

    @Override
    public boolean isActive() {
        return super.isActive() && control.enabled();
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (!isActive()) return;
        control.set(!control.get());
        onChanged.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean enabled = control.enabled();
        boolean on = control.get();
        int right = getX() + getWidth();
        int textY = CausticaPaint.textBaseline(font, getY(), getHeight());

        if (isHovered && enabled) {
            graphics.fill(getX(), getY(), right, getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }

        int switchX = right - CausticaTheme.CONTENT_PAD - CausticaTheme.TOGGLE_WIDTH;
        CausticaPaint.textClipped(graphics, font, control.label(), getX() + CausticaTheme.CONTENT_PAD, textY,
                switchX - getX() - CausticaTheme.CONTENT_PAD * 2,
                CausticaTheme.textColour(enabled, isHovered || isFocused()));

        int switchY = getY() + (getHeight() - CausticaTheme.TOGGLE_HEIGHT) / 2;
        int trackColour = on ? accent : CausticaTheme.TOGGLE_OFF;
        graphics.fill(switchX, switchY, switchX + CausticaTheme.TOGGLE_WIDTH,
                switchY + CausticaTheme.TOGGLE_HEIGHT, enabled ? trackColour : CausticaTheme.dimmed(trackColour));

        int knobX = on ? switchX + CausticaTheme.TOGGLE_WIDTH - CausticaTheme.TOGGLE_HEIGHT : switchX;
        int knobColour = isHovered || isFocused() ? CausticaTheme.KNOB_HOVER : CausticaTheme.KNOB;
        graphics.fill(knobX, switchY, knobX + CausticaTheme.TOGGLE_HEIGHT, switchY + CausticaTheme.TOGGLE_HEIGHT,
                enabled ? knobColour : CausticaTheme.dimmed(knobColour));

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, switchX, switchY, CausticaTheme.TOGGLE_WIDTH,
                    CausticaTheme.TOGGLE_HEIGHT, accent);
        }
        handleCursor(graphics);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, control.label());
        output.add(NarratedElementType.USAGE, Component.translatable(
                control.get() ? "options.on" : "options.off"));
    }
}
